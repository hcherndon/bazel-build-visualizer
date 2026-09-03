package com.holtherndon.bazelviz.ui.timeline;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional bridge from macOS's native trackpad magnification gesture. */
final class MacMagnificationSupport {

  private static final Logger log = LoggerFactory.getLogger(MacMagnificationSupport.class);
  private static final String PACKAGE = "com.apple.eawt.event";
  private static final AtomicBoolean failureLogged = new AtomicBoolean();

  private MacMagnificationSupport() {}

  interface Registration extends AutoCloseable {
    boolean available();

    @Override
    void close();
  }

  private static final Registration UNAVAILABLE =
      new Registration() {
        @Override
        public boolean available() {
          return false;
        }

        @Override
        public void close() {}
      };

  /**
   * Registers a native pinch listener when the macOS JDK API is present and exported to the
   * application. It is deliberately reflective: the package does not exist in Linux JDK images and
   * is not a supported Java SE API. Failure leaves Control/Command-wheel and the zoom buttons
   * intact.
   */
  static Registration install(JComponent component, DoubleConsumer magnificationHandler) {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
      return UNAVAILABLE;
    }
    try {
      Class<?> utilities = Class.forName(PACKAGE + ".GestureUtilities");
      Module desktop = utilities.getModule();
      if (!desktop.isExported(PACKAGE, MacMagnificationSupport.class.getModule())) {
        logUnavailable("the java.desktop gesture package is not exported");
        return UNAVAILABLE;
      }
      Class<?> gestureListener = Class.forName(PACKAGE + ".GestureListener");
      Class<?> magnificationListener = Class.forName(PACKAGE + ".MagnificationListener");
      Class<?> magnificationEvent = Class.forName(PACKAGE + ".MagnificationEvent");
      Method value = magnificationEvent.getMethod("getMagnification");
      Method consume = magnificationEvent.getMethod("consume");
      InvocationHandler handler =
          (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
              return switch (method.getName()) {
                case "toString" -> "Bazel Build Visualizer magnification listener";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
              };
            }
            if ("magnify".equals(method.getName()) && arguments != null && arguments.length == 1) {
              double magnification = ((Number) value.invoke(arguments[0])).doubleValue();
              Runnable deliver = () -> magnificationHandler.accept(magnification);
              if (SwingUtilities.isEventDispatchThread()) {
                deliver.run();
              } else {
                SwingUtilities.invokeLater(deliver);
              }
              consume.invoke(arguments[0]);
            }
            return null;
          };
      Object listener =
          Proxy.newProxyInstance(
              magnificationListener.getClassLoader(),
              new Class<?>[] {magnificationListener},
              handler);
      Method add = utilities.getMethod("addGestureListenerTo", JComponent.class, gestureListener);
      Method remove =
          utilities.getMethod("removeGestureListenerFrom", JComponent.class, gestureListener);
      add.invoke(null, component, listener);
      return new Registration() {
        private boolean open = true;

        @Override
        public boolean available() {
          return open;
        }

        @Override
        public void close() {
          if (!open) {
            return;
          }
          open = false;
          try {
            remove.invoke(null, component, listener);
          } catch (ReflectiveOperationException | RuntimeException failure) {
            log.debug("removing the optional macOS magnification listener", failure);
          }
        }
      };
    } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
      logUnavailable(failure.toString());
      return UNAVAILABLE;
    }
  }

  /** Converts Apple's relative magnification delta into a positive zoom factor. */
  static double zoomFactor(double magnification) {
    if (!Double.isFinite(magnification)) {
      return 1.0;
    }
    return Math.exp(Math.clamp(magnification, -1.0, 1.0));
  }

  private static void logUnavailable(String why) {
    if (failureLogged.compareAndSet(false, true)) {
      log.debug("native macOS pinch zoom is unavailable: {}", why);
    }
  }
}
