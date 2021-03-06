package com.squareup.okhttp.internal.http;

import com.newrelic.agent.android.instrumentation.TransactionStateUtil;
import com.newrelic.agent.android.instrumentation.okhttp2.OkHttp2Instrumentation;
import com.squareup.okhttp.Address;
import com.squareup.okhttp.CertificatePinner;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.Interceptor.Chain;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.Response.Builder;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.InternalCache;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.Version;
import com.squareup.okhttp.internal.http.CacheStrategy.Factory;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.ProtocolException;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

public final class HttpEngine {
    private static final ResponseBody EMPTY_BODY = new C43071();
    public final boolean bufferRequestBody;
    private BufferedSink bufferedRequestBody;
    private Response cacheResponse;
    private CacheStrategy cacheStrategy;
    private final boolean callerWritesRequestBody;
    final OkHttpClient client;
    private final boolean forWebSocket;
    private HttpStream httpStream;
    private Request networkRequest;
    private final Response priorResponse;
    private Sink requestBodyOut;
    long sentRequestMillis = -1;
    private CacheRequest storeRequest;
    public final StreamAllocation streamAllocation;
    private boolean transparentGzip;
    private final Request userRequest;
    private Response userResponse;

    /* renamed from: com.squareup.okhttp.internal.http.HttpEngine$1 */
    static class C43071 extends ResponseBody {
        C43071() {
        }

        public MediaType contentType() {
            return null;
        }

        public long contentLength() {
            return 0;
        }

        public BufferedSource source() {
            return new Buffer();
        }
    }

    class NetworkInterceptorChain implements Chain {
        private int calls;
        private final int index;
        private final Request request;

        NetworkInterceptorChain(int index, Request request) {
            this.index = index;
            this.request = request;
        }

        public Connection connection() {
            return HttpEngine.this.streamAllocation.connection();
        }

        public Request request() {
            return this.request;
        }

        public Response proceed(Request request) throws IOException {
            this.calls++;
            if (this.index > 0) {
                Interceptor caller = (Interceptor) HttpEngine.this.client.networkInterceptors().get(this.index - 1);
                Address address = connection().getRoute().getAddress();
                if (!request.httpUrl().host().equals(address.getUriHost()) || request.httpUrl().port() != address.getUriPort()) {
                    throw new IllegalStateException("network interceptor " + caller + " must retain the same host and port");
                } else if (this.calls > 1) {
                    throw new IllegalStateException("network interceptor " + caller + " must call proceed() exactly once");
                }
            }
            if (this.index < HttpEngine.this.client.networkInterceptors().size()) {
                NetworkInterceptorChain chain = new NetworkInterceptorChain(this.index + 1, request);
                Interceptor interceptor = (Interceptor) HttpEngine.this.client.networkInterceptors().get(this.index);
                Response intercept = interceptor.intercept(chain);
                if (chain.calls != 1) {
                    throw new IllegalStateException("network interceptor " + interceptor + " must call proceed() exactly once");
                } else if (intercept != null) {
                    return intercept;
                } else {
                    throw new NullPointerException("network interceptor " + interceptor + " returned null");
                }
            }
            HttpEngine.this.httpStream.writeRequestHeaders(request);
            HttpEngine.this.networkRequest = request;
            if (HttpEngine.this.permitsRequestBody(request) && request.body() != null) {
                BufferedSink bufferedRequestBody = Okio.buffer(HttpEngine.this.httpStream.createRequestBody(request, request.body().contentLength()));
                request.body().writeTo(bufferedRequestBody);
                bufferedRequestBody.close();
            }
            Response response = HttpEngine.this.readNetworkResponse();
            int code = response.code();
            if ((code != 204 && code != 205) || response.body().contentLength() <= 0) {
                return response;
            }
            throw new ProtocolException("HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
        }
    }

    public HttpEngine(OkHttpClient client, Request request, boolean bufferRequestBody, boolean callerWritesRequestBody, boolean forWebSocket, StreamAllocation streamAllocation, RetryableSink requestBodyOut, Response priorResponse) {
        this.client = client;
        this.userRequest = request;
        this.bufferRequestBody = bufferRequestBody;
        this.callerWritesRequestBody = callerWritesRequestBody;
        this.forWebSocket = forWebSocket;
        if (streamAllocation == null) {
            streamAllocation = new StreamAllocation(client.getConnectionPool(), createAddress(client, request));
        }
        this.streamAllocation = streamAllocation;
        this.requestBodyOut = requestBodyOut;
        this.priorResponse = priorResponse;
    }

    public void sendRequest() throws RequestException, RouteException, IOException {
        if (this.cacheStrategy == null) {
            if (this.httpStream != null) {
                throw new IllegalStateException();
            }
            Request request = networkRequest(this.userRequest);
            InternalCache responseCache = Internal.instance.internalCache(this.client);
            Response cacheCandidate = responseCache != null ? responseCache.get(request) : null;
            this.cacheStrategy = new Factory(System.currentTimeMillis(), request, cacheCandidate).get();
            this.networkRequest = this.cacheStrategy.networkRequest;
            this.cacheResponse = this.cacheStrategy.cacheResponse;
            if (responseCache != null) {
                responseCache.trackResponse(this.cacheStrategy);
            }
            if (cacheCandidate != null && this.cacheResponse == null) {
                Util.closeQuietly(cacheCandidate.body());
            }
            if (this.networkRequest != null) {
                this.httpStream = connect();
                this.httpStream.setHttpEngine(this);
                if (this.callerWritesRequestBody && permitsRequestBody(this.networkRequest) && this.requestBodyOut == null) {
                    long contentLength = OkHeaders.contentLength(request);
                    if (!this.bufferRequestBody) {
                        this.httpStream.writeRequestHeaders(this.networkRequest);
                        this.requestBodyOut = this.httpStream.createRequestBody(this.networkRequest, contentLength);
                        return;
                    } else if (contentLength > 2147483647L) {
                        throw new IllegalStateException("Use setFixedLengthStreamingMode() or setChunkedStreamingMode() for requests larger than 2 GiB.");
                    } else if (contentLength != -1) {
                        this.httpStream.writeRequestHeaders(this.networkRequest);
                        this.requestBodyOut = new RetryableSink((int) contentLength);
                        return;
                    } else {
                        this.requestBodyOut = new RetryableSink();
                        return;
                    }
                }
                return;
            }
            if (this.cacheResponse != null) {
                Response response = this.cacheResponse;
                this.userResponse = (!(response instanceof Builder) ? response.newBuilder() : OkHttp2Instrumentation.newBuilder((Builder) response)).request(this.userRequest).priorResponse(stripBody(this.priorResponse)).cacheResponse(stripBody(this.cacheResponse)).build();
            } else {
                Builder message = new Builder().request(this.userRequest).priorResponse(stripBody(this.priorResponse)).protocol(Protocol.HTTP_1_1).code(504).message("Unsatisfiable Request (only-if-cached)");
                ResponseBody responseBody = EMPTY_BODY;
                this.userResponse = (!(message instanceof Builder) ? message.body(responseBody) : OkHttp2Instrumentation.body(message, responseBody)).build();
            }
            this.userResponse = unzip(this.userResponse);
        }
    }

    private HttpStream connect() throws RouteException, RequestException, IOException {
        return this.streamAllocation.newStream(this.client.getConnectTimeout(), this.client.getReadTimeout(), this.client.getWriteTimeout(), this.client.getRetryOnConnectionFailure(), !this.networkRequest.method().equals("GET"));
    }

    private static Response stripBody(Response response) {
        if (response == null || response.body() == null) {
            return response;
        }
        Builder newBuilder = !(response instanceof Builder) ? response.newBuilder() : OkHttp2Instrumentation.newBuilder((Builder) response);
        return (!(newBuilder instanceof Builder) ? newBuilder.body(null) : OkHttp2Instrumentation.body(newBuilder, null)).build();
    }

    public void writingRequestHeaders() {
        if (this.sentRequestMillis != -1) {
            throw new IllegalStateException();
        }
        this.sentRequestMillis = System.currentTimeMillis();
    }

    /* Access modifiers changed, original: 0000 */
    public boolean permitsRequestBody(Request request) {
        return HttpMethod.permitsRequestBody(request.method());
    }

    public Sink getRequestBody() {
        if (this.cacheStrategy != null) {
            return this.requestBodyOut;
        }
        throw new IllegalStateException();
    }

    public BufferedSink getBufferedRequestBody() {
        BufferedSink result = this.bufferedRequestBody;
        if (result != null) {
            return result;
        }
        BufferedSink buffer;
        Sink requestBody = getRequestBody();
        if (requestBody != null) {
            buffer = Okio.buffer(requestBody);
            this.bufferedRequestBody = buffer;
        } else {
            buffer = null;
        }
        return buffer;
    }

    public boolean hasResponse() {
        return this.userResponse != null;
    }

    public Request getRequest() {
        return this.userRequest;
    }

    public Response getResponse() {
        if (this.userResponse != null) {
            return this.userResponse;
        }
        throw new IllegalStateException();
    }

    public Connection getConnection() {
        return this.streamAllocation.connection();
    }

    public HttpEngine recover(RouteException e) {
        if (!this.streamAllocation.recover(e) || !this.client.getRetryOnConnectionFailure()) {
            return null;
        }
        return new HttpEngine(this.client, this.userRequest, this.bufferRequestBody, this.callerWritesRequestBody, this.forWebSocket, close(), (RetryableSink) this.requestBodyOut, this.priorResponse);
    }

    public HttpEngine recover(IOException e, Sink requestBodyOut) {
        if (!this.streamAllocation.recover(e, requestBodyOut) || !this.client.getRetryOnConnectionFailure()) {
            return null;
        }
        return new HttpEngine(this.client, this.userRequest, this.bufferRequestBody, this.callerWritesRequestBody, this.forWebSocket, close(), (RetryableSink) requestBodyOut, this.priorResponse);
    }

    public HttpEngine recover(IOException e) {
        return recover(e, this.requestBodyOut);
    }

    private void maybeCache() throws IOException {
        InternalCache responseCache = Internal.instance.internalCache(this.client);
        if (responseCache != null) {
            if (CacheStrategy.isCacheable(this.userResponse, this.networkRequest)) {
                this.storeRequest = responseCache.put(stripBody(this.userResponse));
            } else if (HttpMethod.invalidatesCache(this.networkRequest.method())) {
                try {
                    responseCache.remove(this.networkRequest);
                } catch (IOException e) {
                }
            }
        }
    }

    public void releaseStreamAllocation() throws IOException {
        this.streamAllocation.release();
    }

    public void cancel() {
        this.streamAllocation.cancel();
    }

    public StreamAllocation close() {
        if (this.bufferedRequestBody != null) {
            Util.closeQuietly(this.bufferedRequestBody);
        } else if (this.requestBodyOut != null) {
            Util.closeQuietly(this.requestBodyOut);
        }
        if (this.userResponse != null) {
            Util.closeQuietly(this.userResponse.body());
        } else {
            this.streamAllocation.connectionFailed();
        }
        return this.streamAllocation;
    }

    private Response unzip(Response response) throws IOException {
        if (!this.transparentGzip || !"gzip".equalsIgnoreCase(this.userResponse.header("Content-Encoding")) || response.body() == null) {
            return response;
        }
        Source responseBody = new GzipSource(response.body().source());
        Headers strippedHeaders = response.headers().newBuilder().removeAll("Content-Encoding").removeAll(TransactionStateUtil.CONTENT_LENGTH_HEADER).build();
        Builder headers = (!(response instanceof Builder) ? response.newBuilder() : OkHttp2Instrumentation.newBuilder((Builder) response)).headers(strippedHeaders);
        RealResponseBody realResponseBody = new RealResponseBody(strippedHeaders, Okio.buffer(responseBody));
        return (!(headers instanceof Builder) ? headers.body(realResponseBody) : OkHttp2Instrumentation.body(headers, realResponseBody)).build();
    }

    public static boolean hasBody(Response response) {
        if (response.request().method().equals("HEAD")) {
            return false;
        }
        int responseCode = response.code();
        if ((responseCode < 100 || responseCode >= 200) && responseCode != 204 && responseCode != 304) {
            return true;
        }
        if (OkHeaders.contentLength(response) != -1 || "chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
            return true;
        }
        return false;
    }

    private Request networkRequest(Request request) throws IOException {
        Request.Builder result = request.newBuilder();
        if (request.header("Host") == null) {
            result.header("Host", Util.hostHeader(request.httpUrl()));
        }
        if (request.header("Connection") == null) {
            result.header("Connection", "Keep-Alive");
        }
        if (request.header("Accept-Encoding") == null) {
            this.transparentGzip = true;
            result.header("Accept-Encoding", "gzip");
        }
        CookieHandler cookieHandler = this.client.getCookieHandler();
        if (cookieHandler != null) {
            Request build;
            if (result instanceof Request.Builder) {
                build = OkHttp2Instrumentation.build(result);
            } else {
                build = result.build();
            }
            OkHeaders.addCookies(result, cookieHandler.get(request.uri(), OkHeaders.toMultimap(build.headers(), null)));
        }
        if (request.header("User-Agent") == null) {
            result.header("User-Agent", Version.userAgent());
        }
        return !(result instanceof Request.Builder) ? result.build() : OkHttp2Instrumentation.build(result);
    }

    public void readResponse() throws IOException {
        if (this.userResponse == null) {
            if (this.networkRequest == null && this.cacheResponse == null) {
                throw new IllegalStateException("call sendRequest() first!");
            } else if (this.networkRequest != null) {
                Response networkResponse;
                if (this.forWebSocket) {
                    this.httpStream.writeRequestHeaders(this.networkRequest);
                    networkResponse = readNetworkResponse();
                } else if (this.callerWritesRequestBody) {
                    if (this.bufferedRequestBody != null && this.bufferedRequestBody.buffer().size() > 0) {
                        this.bufferedRequestBody.emit();
                    }
                    if (this.sentRequestMillis == -1) {
                        if (OkHeaders.contentLength(this.networkRequest) == -1 && (this.requestBodyOut instanceof RetryableSink)) {
                            Request build;
                            Request.Builder header = this.networkRequest.newBuilder().header(TransactionStateUtil.CONTENT_LENGTH_HEADER, Long.toString(((RetryableSink) this.requestBodyOut).contentLength()));
                            if (header instanceof Request.Builder) {
                                build = OkHttp2Instrumentation.build(header);
                            } else {
                                build = header.build();
                            }
                            this.networkRequest = build;
                        }
                        this.httpStream.writeRequestHeaders(this.networkRequest);
                    }
                    if (this.requestBodyOut != null) {
                        if (this.bufferedRequestBody != null) {
                            this.bufferedRequestBody.close();
                        } else {
                            this.requestBodyOut.close();
                        }
                        if (this.requestBodyOut instanceof RetryableSink) {
                            this.httpStream.writeRequestBody((RetryableSink) this.requestBodyOut);
                        }
                    }
                    networkResponse = readNetworkResponse();
                } else {
                    networkResponse = new NetworkInterceptorChain(0, this.networkRequest).proceed(this.networkRequest);
                }
                receiveHeaders(networkResponse.headers());
                if (this.cacheResponse != null) {
                    if (validate(this.cacheResponse, networkResponse)) {
                        Builder newBuilder;
                        Response response = this.cacheResponse;
                        if (response instanceof Builder) {
                            newBuilder = OkHttp2Instrumentation.newBuilder((Builder) response);
                        } else {
                            newBuilder = response.newBuilder();
                        }
                        this.userResponse = newBuilder.request(this.userRequest).priorResponse(stripBody(this.priorResponse)).headers(combine(this.cacheResponse.headers(), networkResponse.headers())).cacheResponse(stripBody(this.cacheResponse)).networkResponse(stripBody(networkResponse)).build();
                        networkResponse.body().close();
                        releaseStreamAllocation();
                        InternalCache responseCache = Internal.instance.internalCache(this.client);
                        responseCache.trackConditionalCacheHit();
                        responseCache.update(this.cacheResponse, stripBody(this.userResponse));
                        this.userResponse = unzip(this.userResponse);
                        return;
                    }
                    Util.closeQuietly(this.cacheResponse.body());
                }
                this.userResponse = (!(networkResponse instanceof Builder) ? networkResponse.newBuilder() : OkHttp2Instrumentation.newBuilder((Builder) networkResponse)).request(this.userRequest).priorResponse(stripBody(this.priorResponse)).cacheResponse(stripBody(this.cacheResponse)).networkResponse(stripBody(networkResponse)).build();
                if (hasBody(this.userResponse)) {
                    maybeCache();
                    this.userResponse = unzip(cacheWritingResponse(this.storeRequest, this.userResponse));
                }
            }
        }
    }

    private Response readNetworkResponse() throws IOException {
        this.httpStream.finishRequest();
        Response networkResponse = this.httpStream.readResponseHeaders().request(this.networkRequest).handshake(this.streamAllocation.connection().getHandshake()).header(OkHeaders.SENT_MILLIS, Long.toString(this.sentRequestMillis)).header(OkHeaders.RECEIVED_MILLIS, Long.toString(System.currentTimeMillis())).build();
        if (!this.forWebSocket) {
            Builder newBuilder = !(networkResponse instanceof Builder) ? networkResponse.newBuilder() : OkHttp2Instrumentation.newBuilder((Builder) networkResponse);
            ResponseBody openResponseBody = this.httpStream.openResponseBody(networkResponse);
            networkResponse = (!(newBuilder instanceof Builder) ? newBuilder.body(openResponseBody) : OkHttp2Instrumentation.body(newBuilder, openResponseBody)).build();
        }
        if ("close".equalsIgnoreCase(networkResponse.request().header("Connection")) || "close".equalsIgnoreCase(networkResponse.header("Connection"))) {
            this.streamAllocation.noNewStreams();
        }
        return networkResponse;
    }

    private Response cacheWritingResponse(final CacheRequest cacheRequest, Response response) throws IOException {
        if (cacheRequest == null) {
            return response;
        }
        Sink cacheBodyUnbuffered = cacheRequest.body();
        if (cacheBodyUnbuffered == null) {
            return response;
        }
        final BufferedSource source = response.body().source();
        final BufferedSink cacheBody = Okio.buffer(cacheBodyUnbuffered);
        Source cacheWritingSource = new Source() {
            boolean cacheRequestClosed;

            public long read(Buffer sink, long byteCount) throws IOException {
                try {
                    long bytesRead = source.read(sink, byteCount);
                    if (bytesRead == -1) {
                        if (!this.cacheRequestClosed) {
                            this.cacheRequestClosed = true;
                            cacheBody.close();
                        }
                        return -1;
                    }
                    sink.copyTo(cacheBody.buffer(), sink.size() - bytesRead, bytesRead);
                    cacheBody.emitCompleteSegments();
                    return bytesRead;
                } catch (IOException e) {
                    if (!this.cacheRequestClosed) {
                        this.cacheRequestClosed = true;
                        cacheRequest.abort();
                    }
                    throw e;
                }
            }

            public Timeout timeout() {
                return source.timeout();
            }

            public void close() throws IOException {
                if (!(this.cacheRequestClosed || Util.discard(this, 100, TimeUnit.MILLISECONDS))) {
                    this.cacheRequestClosed = true;
                    cacheRequest.abort();
                }
                source.close();
            }
        };
        Builder newBuilder = !(response instanceof Builder) ? response.newBuilder() : OkHttp2Instrumentation.newBuilder((Builder) response);
        RealResponseBody realResponseBody = new RealResponseBody(response.headers(), Okio.buffer(cacheWritingSource));
        return (!(newBuilder instanceof Builder) ? newBuilder.body(realResponseBody) : OkHttp2Instrumentation.body(newBuilder, realResponseBody)).build();
    }

    private static boolean validate(Response cached, Response network) {
        if (network.code() == 304) {
            return true;
        }
        Date lastModified = cached.headers().getDate("Last-Modified");
        if (lastModified != null) {
            Date networkLastModified = network.headers().getDate("Last-Modified");
            if (networkLastModified != null && networkLastModified.getTime() < lastModified.getTime()) {
                return true;
            }
        }
        return false;
    }

    private static Headers combine(Headers cachedHeaders, Headers networkHeaders) throws IOException {
        int i;
        String fieldName;
        Headers.Builder result = new Headers.Builder();
        int size = cachedHeaders.size();
        for (i = 0; i < size; i++) {
            fieldName = cachedHeaders.name(i);
            String value = cachedHeaders.value(i);
            if (!("Warning".equalsIgnoreCase(fieldName) && value.startsWith("1")) && (!OkHeaders.isEndToEnd(fieldName) || networkHeaders.get(fieldName) == null)) {
                result.add(fieldName, value);
            }
        }
        size = networkHeaders.size();
        for (i = 0; i < size; i++) {
            fieldName = networkHeaders.name(i);
            if (!TransactionStateUtil.CONTENT_LENGTH_HEADER.equalsIgnoreCase(fieldName) && OkHeaders.isEndToEnd(fieldName)) {
                result.add(fieldName, networkHeaders.value(i));
            }
        }
        return result.build();
    }

    public void receiveHeaders(Headers headers) throws IOException {
        CookieHandler cookieHandler = this.client.getCookieHandler();
        if (cookieHandler != null) {
            cookieHandler.put(this.userRequest.uri(), OkHeaders.toMultimap(headers, null));
        }
    }

    /* JADX WARNING: Missing block: B:23:0x0069, code skipped:
            if (r12.client.getFollowRedirects() == false) goto L_?;
     */
    /* JADX WARNING: Missing block: B:24:0x006b, code skipped:
            r1 = r12.userResponse.header(com.mcdonalds.sdk.connectors.middleware.model.DCSFavorite.TYPE_LOCATION);
     */
    /* JADX WARNING: Missing block: B:25:0x0073, code skipped:
            if (r1 == null) goto L_?;
     */
    /* JADX WARNING: Missing block: B:26:0x0075, code skipped:
            r8 = r12.userRequest.httpUrl().resolve(r1);
     */
    /* JADX WARNING: Missing block: B:27:0x007f, code skipped:
            if (r8 == null) goto L_?;
     */
    /* JADX WARNING: Missing block: B:29:0x0093, code skipped:
            if (r8.scheme().equals(r12.userRequest.httpUrl().scheme()) != false) goto L_0x009d;
     */
    /* JADX WARNING: Missing block: B:31:0x009b, code skipped:
            if (r12.client.getFollowSslRedirects() == false) goto L_?;
     */
    /* JADX WARNING: Missing block: B:32:0x009d, code skipped:
            r3 = r12.userRequest.newBuilder();
     */
    /* JADX WARNING: Missing block: B:33:0x00a7, code skipped:
            if (com.squareup.okhttp.internal.http.HttpMethod.permitsRequestBody(r2) == false) goto L_0x00c3;
     */
    /* JADX WARNING: Missing block: B:35:0x00ad, code skipped:
            if (com.squareup.okhttp.internal.http.HttpMethod.redirectsToGet(r2) == false) goto L_0x00dc;
     */
    /* JADX WARNING: Missing block: B:36:0x00af, code skipped:
            r3.method("GET", null);
     */
    /* JADX WARNING: Missing block: B:37:0x00b4, code skipped:
            r3.removeHeader("Transfer-Encoding");
            r3.removeHeader(com.newrelic.agent.android.instrumentation.TransactionStateUtil.CONTENT_LENGTH_HEADER);
            r3.removeHeader(com.newrelic.agent.android.instrumentation.TransactionStateUtil.CONTENT_TYPE_HEADER);
     */
    /* JADX WARNING: Missing block: B:39:0x00c7, code skipped:
            if (sameConnection(r8) != false) goto L_0x00ce;
     */
    /* JADX WARNING: Missing block: B:40:0x00c9, code skipped:
            r3.removeHeader("Authorization");
     */
    /* JADX WARNING: Missing block: B:41:0x00ce, code skipped:
            r9 = r3.url(r8);
     */
    /* JADX WARNING: Missing block: B:42:0x00d4, code skipped:
            if ((r9 instanceof com.squareup.okhttp.Request.Builder) != false) goto L_0x00e0;
     */
    /* JADX WARNING: Missing block: B:44:0x00dc, code skipped:
            r3.method(r2, null);
     */
    /* JADX WARNING: Missing block: B:47:?, code skipped:
            return com.squareup.okhttp.internal.http.OkHeaders.processAuthHeader(r12.client.getAuthenticator(), r12.userResponse, r7);
     */
    /* JADX WARNING: Missing block: B:49:?, code skipped:
            return null;
     */
    /* JADX WARNING: Missing block: B:50:?, code skipped:
            return null;
     */
    /* JADX WARNING: Missing block: B:51:?, code skipped:
            return null;
     */
    /* JADX WARNING: Missing block: B:52:?, code skipped:
            return null;
     */
    /* JADX WARNING: Missing block: B:53:?, code skipped:
            return r9.build();
     */
    /* JADX WARNING: Missing block: B:54:?, code skipped:
            return com.newrelic.agent.android.instrumentation.okhttp2.OkHttp2Instrumentation.build(r9);
     */
    public com.squareup.okhttp.Request followUpRequest() throws java.io.IOException {
        /*
        r12 = this;
        r9 = 0;
        r10 = r12.userResponse;
        if (r10 != 0) goto L_0x000b;
    L_0x0005:
        r9 = new java.lang.IllegalStateException;
        r9.<init>();
        throw r9;
    L_0x000b:
        r10 = r12.streamAllocation;
        r0 = r10.connection();
        if (r0 == 0) goto L_0x002d;
    L_0x0013:
        r5 = r0.getRoute();
    L_0x0017:
        if (r5 == 0) goto L_0x002f;
    L_0x0019:
        r7 = r5.getProxy();
    L_0x001d:
        r10 = r12.userResponse;
        r4 = r10.code();
        r10 = r12.userRequest;
        r2 = r10.method();
        switch(r4) {
            case 300: goto L_0x0063;
            case 301: goto L_0x0063;
            case 302: goto L_0x0063;
            case 303: goto L_0x0063;
            case 307: goto L_0x0053;
            case 308: goto L_0x0053;
            case 401: goto L_0x0046;
            case 407: goto L_0x0036;
            default: goto L_0x002c;
        };
    L_0x002c:
        return r9;
    L_0x002d:
        r5 = r9;
        goto L_0x0017;
    L_0x002f:
        r10 = r12.client;
        r7 = r10.getProxy();
        goto L_0x001d;
    L_0x0036:
        r9 = r7.type();
        r10 = java.net.Proxy.Type.HTTP;
        if (r9 == r10) goto L_0x0046;
    L_0x003e:
        r9 = new java.net.ProtocolException;
        r10 = "Received HTTP_PROXY_AUTH (407) code while not using proxy";
        r9.<init>(r10);
        throw r9;
    L_0x0046:
        r9 = r12.client;
        r9 = r9.getAuthenticator();
        r10 = r12.userResponse;
        r9 = com.squareup.okhttp.internal.http.OkHeaders.processAuthHeader(r9, r10, r7);
        goto L_0x002c;
    L_0x0053:
        r10 = "GET";
        r10 = r2.equals(r10);
        if (r10 != 0) goto L_0x0063;
    L_0x005b:
        r10 = "HEAD";
        r10 = r2.equals(r10);
        if (r10 == 0) goto L_0x002c;
    L_0x0063:
        r10 = r12.client;
        r10 = r10.getFollowRedirects();
        if (r10 == 0) goto L_0x002c;
    L_0x006b:
        r10 = r12.userResponse;
        r11 = "Location";
        r1 = r10.header(r11);
        if (r1 == 0) goto L_0x002c;
    L_0x0075:
        r10 = r12.userRequest;
        r10 = r10.httpUrl();
        r8 = r10.resolve(r1);
        if (r8 == 0) goto L_0x002c;
    L_0x0081:
        r10 = r8.scheme();
        r11 = r12.userRequest;
        r11 = r11.httpUrl();
        r11 = r11.scheme();
        r6 = r10.equals(r11);
        if (r6 != 0) goto L_0x009d;
    L_0x0095:
        r10 = r12.client;
        r10 = r10.getFollowSslRedirects();
        if (r10 == 0) goto L_0x002c;
    L_0x009d:
        r10 = r12.userRequest;
        r3 = r10.newBuilder();
        r10 = com.squareup.okhttp.internal.http.HttpMethod.permitsRequestBody(r2);
        if (r10 == 0) goto L_0x00c3;
    L_0x00a9:
        r10 = com.squareup.okhttp.internal.http.HttpMethod.redirectsToGet(r2);
        if (r10 == 0) goto L_0x00dc;
    L_0x00af:
        r10 = "GET";
        r3.method(r10, r9);
    L_0x00b4:
        r9 = "Transfer-Encoding";
        r3.removeHeader(r9);
        r9 = "Content-Length";
        r3.removeHeader(r9);
        r9 = "Content-Type";
        r3.removeHeader(r9);
    L_0x00c3:
        r9 = r12.sameConnection(r8);
        if (r9 != 0) goto L_0x00ce;
    L_0x00c9:
        r9 = "Authorization";
        r3.removeHeader(r9);
    L_0x00ce:
        r9 = r3.url(r8);
        r10 = r9 instanceof com.squareup.okhttp.Request.Builder;
        if (r10 != 0) goto L_0x00e0;
    L_0x00d6:
        r9 = r9.build();
        goto L_0x002c;
    L_0x00dc:
        r3.method(r2, r9);
        goto L_0x00b4;
    L_0x00e0:
        r9 = (com.squareup.okhttp.Request.Builder) r9;
        r9 = com.newrelic.agent.android.instrumentation.okhttp2.OkHttp2Instrumentation.build(r9);
        goto L_0x002c;
        */
        throw new UnsupportedOperationException("Method not decompiled: com.squareup.okhttp.internal.http.HttpEngine.followUpRequest():com.squareup.okhttp.Request");
    }

    public boolean sameConnection(HttpUrl followUp) {
        HttpUrl url = this.userRequest.httpUrl();
        return url.host().equals(followUp.host()) && url.port() == followUp.port() && url.scheme().equals(followUp.scheme());
    }

    private static Address createAddress(OkHttpClient client, Request request) {
        SSLSocketFactory sslSocketFactory = null;
        HostnameVerifier hostnameVerifier = null;
        CertificatePinner certificatePinner = null;
        if (request.isHttps()) {
            sslSocketFactory = client.getSslSocketFactory();
            hostnameVerifier = client.getHostnameVerifier();
            certificatePinner = client.getCertificatePinner();
        }
        return new Address(request.httpUrl().host(), request.httpUrl().port(), client.getDns(), client.getSocketFactory(), sslSocketFactory, hostnameVerifier, certificatePinner, client.getAuthenticator(), client.getProxy(), client.getProtocols(), client.getConnectionSpecs(), client.getProxySelector());
    }
}
