package org.maplibre.maplibregl;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import io.flutter.plugin.common.MethodChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.maplibre.android.module.http.HttpRequestUtil;

/**
 * Builds the one OkHttp client MapLibre uses once Dart changes an HTTP setting: the global headers
 * of setHttpHeaders, the per-URL headers of the controller's setCustomHeaders and the limits of
 * setOfflineMaxConcurrentRequests. Each call replaces its part of an immutable {@link Settings} and
 * installs a client built from all of it, so no call undoes another.
 */
abstract class MapLibreHttpRequestUtil {
  private static final String TAG = "MapLibreHttpRequestUtil";

  /** The HTTP client could not be rebuilt with the new settings. */
  private static final String HTTP_CLIENT_ERROR = "HTTP_CLIENT_ERROR";

  private static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

  /** The per-host limit of the SDK's own client, kept while Dart sets none. */
  static final int DEFAULT_MAX_REQUESTS_PER_HOST = 20;

  /** Stands for a filter pattern that does not compile: it matches no URL, as on iOS. */
  private static final Pattern MATCHES_NOTHING = Pattern.compile("(?!)");

  /** What the client is built from. Never changed: a call publishes a new one. */
  static final class Settings {
    static final Settings EMPTY =
        new Settings(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), null, null);

    final Map<String, String> headers;
    final Map<String, String> customHeaders;
    final List<Pattern> customHeadersFilter;
    @Nullable final Integer maxRequests;
    @Nullable final Integer maxRequestsPerHost;

    private Settings(
        Map<String, String> headers,
        Map<String, String> customHeaders,
        List<Pattern> customHeadersFilter,
        @Nullable Integer maxRequests,
        @Nullable Integer maxRequestsPerHost) {
      this.headers = headers;
      this.customHeaders = customHeaders;
      this.customHeadersFilter = customHeadersFilter;
      this.maxRequests = maxRequests;
      this.maxRequestsPerHost = maxRequestsPerHost;
    }

    Settings withHeaders(Map<String, String> headers) {
      return new Settings(
          copy(headers), customHeaders, customHeadersFilter, maxRequests, maxRequestsPerHost);
    }

    Settings withCustomHeaders(Map<String, String> customHeaders, List<String> filter) {
      List<Pattern> patterns = new ArrayList<>();
      for (String pattern : filter) {
        patterns.add(compile(pattern));
      }
      return new Settings(
          headers,
          copy(customHeaders),
          Collections.unmodifiableList(patterns),
          maxRequests,
          maxRequestsPerHost);
    }

    Settings withLimits(@Nullable Integer maxRequests, @Nullable Integer maxRequestsPerHost) {
      return new Settings(
          headers, customHeaders, customHeadersFilter, maxRequests, maxRequestsPerHost);
    }

    /**
     * The request with the global headers, then the custom headers if a filter pattern is found
     * anywhere in its URL, as on iOS, or there is none. An empty value removes the header.
     */
    Request apply(Request request) {
      Request transformed = transformRequest(request);
      Request.Builder builder = transformed.newBuilder();
      putHeaders(builder, headers);
      if (!customHeaders.isEmpty() && matchesFilter(transformed.url().toString())) {
        putHeaders(builder, customHeaders);
      }
      return builder.build();
    }

    private boolean matchesFilter(String url) {
      if (customHeadersFilter.isEmpty()) {
        return true;
      }
      for (Pattern pattern : customHeadersFilter) {
        if (pattern.matcher(url).find()) {
          return true;
        }
      }
      return false;
    }

    private static void putHeaders(Request.Builder builder, Map<String, String> headers) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        String name = header.getKey();
        if (name == null || name.trim().isEmpty()) {
          continue;
        }
        String value = header.getValue();
        if (value == null || value.trim().isEmpty()) {
          builder.removeHeader(name);
        } else {
          builder.header(name, value);
        }
      }
    }

    private static Pattern compile(String pattern) {
      try {
        return Pattern.compile(pattern);
      } catch (PatternSyntaxException e) {
        Log.w(TAG, "The header filter " + pattern + " is not a valid pattern: " + e.getMessage());
        return MATCHES_NOTHING;
      }
    }

    private static Map<String, String> copy(Map<String, String> map) {
      return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
  }

  /** Runs the Dart transform callback for maps which requested it. */
  private static Request transformRequest(Request request) {
    MapLibreMapController controller = null;
    for (MapLibreMapController candidate :
        MapLibreMapController.activeTransformRequestControllers) {
      if (candidate.transformRequestEnabled) {
        controller = candidate;
        break;
      }
    }
    if (controller == null) return request;

    final CountDownLatch latch = new CountDownLatch(1);
    try {
      if (!controller.transformRequestReady.await(30, TimeUnit.SECONDS)) return request;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return request;
    }
    final Map<String, Object> response = new LinkedHashMap<>();
    final Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("url", request.url().toString());
    arguments.put("resourceType", inferResourceType(request.url().toString()));
    final MethodChannel channel = controller.transformRequestChannel;
    new Handler(Looper.getMainLooper()).post(
        () ->
            channel.invokeMethod(
                "map#transformRequest",
                arguments,
                new MethodChannel.Result() {
                  @Override
                  public void success(Object result) {
                    if (result instanceof Map) {
                      response.putAll((Map<String, Object>) result);
                    }
                    latch.countDown();
                  }

                  @Override
                  public void error(String code, String message, Object details) {
                    latch.countDown();
                  }

                  @Override
                  public void notImplemented() {
                    latch.countDown();
                  }
                }));
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) return request;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return request;
    }

    Request.Builder builder = request.newBuilder();
    Object url = response.get("url");
    if (url instanceof String && !((String) url).isEmpty()) {
      builder.url((String) url);
    }
    Object headers = response.get("headers");
    if (headers instanceof Map) {
      for (Map.Entry<?, ?> header : ((Map<?, ?>) headers).entrySet()) {
        if (!(header.getKey() instanceof String)) continue;
        String name = (String) header.getKey();
        if (name.trim().isEmpty()) continue;
        Object value = header.getValue();
        if (value == null || value.toString().trim().isEmpty()) {
          builder.removeHeader(name);
        } else {
          builder.header(name, value.toString());
        }
      }
    }
    return builder.build();
  }

  private static int inferResourceType(String url) {
    String lower = url.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("/styles/") || lower.endsWith("style.json")) return 1;
    if (lower.contains("/sprites/") || lower.contains("sprite")) {
      return lower.endsWith(".json") ? 6 : 5;
    }
    if (lower.contains("/fonts/") || lower.contains("/glyphs/")) return 4;
    if (lower.contains("/tiles/") || lower.contains("/tile/")
        || lower.endsWith(".pbf") || lower.endsWith(".mvt")) return 3;
    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp")) return 7;
    return 0;
  }

  // Replaced whole under the class lock. A client keeps the instance it was built from, so
  // OkHttp's threads never see a change half made.
  private static volatile Settings settings = Settings.EMPTY;

  /** Where a built client goes. Tests replace it, as the SDK's needs Android. */
  static Consumer<OkHttpClient> install = client -> HttpRequestUtil.setOkHttpClient(client);

  /** Installs the request interceptor before the first map is created. */
  static synchronized void initialize() {
    install.accept(buildClient(settings));
  }

  static void setHttpHeaders(Map<String, String> headers, MethodChannel.Result result) {
    update(current -> current.withHeaders(headers), result);
  }

  static void setCustomHeaders(
      Map<String, String> headers, List<String> filter, MethodChannel.Result result) {
    update(current -> current.withCustomHeaders(headers, filter), result);
  }

  /** The headers of the last setCustomHeaders. */
  static Map<String, String> getCustomHeaders() {
    return settings.customHeaders;
  }

  static void setMaxConcurrentRequests(
      @Nullable Long maxRequests, @Nullable Long maxRequestsPerHost, MethodChannel.Result result) {
    // OkHttp's Dispatcher takes an int of at least 1.
    if (!isValidLimit(maxRequests)) {
      result.error(INVALID_ARGUMENT, limitMessage("maxRequests", maxRequests), null);
      return;
    }
    if (!isValidLimit(maxRequestsPerHost)) {
      result.error(INVALID_ARGUMENT, limitMessage("maxRequestsPerHost", maxRequestsPerHost), null);
      return;
    }
    update(
        current -> current.withLimits(toInteger(maxRequests), toInteger(maxRequestsPerHost)),
        result);
  }

  private static boolean isValidLimit(@Nullable Long value) {
    return value == null || (value >= 1 && value <= Integer.MAX_VALUE);
  }

  private static String limitMessage(String name, Long value) {
    return value < 1
        ? name + " must be >= 1 (got " + value + ")"
        : name + " must be <= " + Integer.MAX_VALUE + " (got " + value + ")";
  }

  @Nullable
  private static Integer toInteger(@Nullable Long value) {
    return value == null ? null : value.intValue();
  }

  /** Installs a client built from the settings {@code change} makes, then publishes them. */
  private static synchronized void update(
      UnaryOperator<Settings> change, MethodChannel.Result result) {
    Settings next = change.apply(settings);
    try {
      install.accept(buildClient(next));
    } catch (RuntimeException e) {
      result.error(HTTP_CLIENT_ERROR, e.getMessage(), null);
      return;
    }
    settings = next;
    result.success(null);
  }

  static OkHttpClient buildClient(Settings settings) {
    Dispatcher dispatcher = new Dispatcher();
    dispatcher.setMaxRequestsPerHost(DEFAULT_MAX_REQUESTS_PER_HOST);
    if (settings.maxRequests != null) {
      dispatcher.setMaxRequests(settings.maxRequests);
    }
    if (settings.maxRequestsPerHost != null) {
      dispatcher.setMaxRequestsPerHost(settings.maxRequestsPerHost);
    }
    return new OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .addNetworkInterceptor(chain -> chain.proceed(settings.apply(chain.request())))
        .build();
  }

  /** Forgets every setting, for tests. */
  static synchronized void resetForTest() {
    settings = Settings.EMPTY;
  }
}
