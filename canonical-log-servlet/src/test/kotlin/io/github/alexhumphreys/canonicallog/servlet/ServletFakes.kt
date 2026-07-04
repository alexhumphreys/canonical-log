package io.github.alexhumphreys.canonicallog.servlet

import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.DispatcherType
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Hand-rolled servlet fakes — this module has no Spring dependency, so there's no
 * `MockHttpServletRequest`. The big servlet interfaces are backed by dynamic proxies that
 * answer only the handful of methods the lifecycle actually calls (method, URI, header,
 * attributes, async start/context, response status); everything else throws so a stray call
 * surfaces loudly. [FakeAsyncContext] is a real class (the interface is small) so tests can
 * fire terminal callbacks directly.
 */
internal class FakeAsyncContext(
    private val request: ServletRequest,
    private val response: ServletResponse,
) : AsyncContext {
    val listeners: MutableList<AsyncListener> = mutableListOf()

    override fun addListener(listener: AsyncListener) {
        listeners += listener
    }

    override fun addListener(listener: AsyncListener, req: ServletRequest, res: ServletResponse) {
        listeners += listener
    }

    override fun getRequest(): ServletRequest = request
    override fun getResponse(): ServletResponse = response
    override fun hasOriginalRequestAndResponse(): Boolean = true
    override fun complete() {
        fireComplete()
    }

    /** Simulate the container delivering the terminal callbacks the filter's listener funnels. */
    fun fireComplete(cause: Throwable? = null) {
        val event = AsyncEvent(this, request, response, cause)
        listeners.toList().forEach { it.onComplete(event) }
    }

    fun fireTimeout() {
        val event = AsyncEvent(this, request, response)
        listeners.toList().forEach { it.onTimeout(event) }
    }

    override fun dispatch(): Unit = unsupported()
    override fun dispatch(path: String): Unit = unsupported()
    override fun dispatch(context: ServletContext, path: String): Unit = unsupported()
    override fun start(run: Runnable): Unit = unsupported()
    override fun <T : AsyncListener> createListener(clazz: Class<T>): T = unsupported()
    override fun setTimeout(timeout: Long): Unit = unsupported()
    override fun getTimeout(): Long = 0

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}

/**
 * A fake [HttpServletRequest] plus its paired response, with mutable attribute state and an
 * async cycle that mirrors the servlet contract closely enough for the filter lifecycle.
 */
internal class FakeRequest(
    private val method: String = "GET",
    private val uri: String = "/",
    private val headers: Map<String, String> = emptyMap(),
    private val asyncSupported: Boolean = false,
    responseStatus: Int = 200,
) {
    private val attributes = mutableMapOf<String, Any?>()
    var asyncStarted: Boolean = false
        private set
    var asyncContext: FakeAsyncContext? = null
        private set

    val response: HttpServletResponse = fakeResponse(responseStatus)

    val request: HttpServletRequest = run {
        val handler = object : InvocationHandler {
            lateinit var self: HttpServletRequest
            override fun invoke(proxy: Any, m: Method, args: Array<out Any?>?): Any? =
                when (m.name) {
                    "getMethod" -> method
                    "getRequestURI" -> uri
                    "getHeader" -> headers[args!![0] as String]
                    "getAttribute" -> attributes[args!![0] as String]
                    "setAttribute" -> { attributes[args!![0] as String] = args[1]; null }
                    "removeAttribute" -> { attributes.remove(args!![0] as String); null }
                    "isAsyncStarted" -> asyncStarted
                    "isAsyncSupported" -> asyncSupported
                    "getAsyncContext" -> asyncContext ?: throw IllegalStateException("async not started")
                    "startAsync" -> {
                        asyncStarted = true
                        FakeAsyncContext(self, response).also { asyncContext = it }
                    }
                    "getDispatcherType" -> DispatcherType.REQUEST
                    "toString" -> "FakeHttpServletRequest($method $uri)"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> throw UnsupportedOperationException("FakeRequest: ${m.name}")
                }
        }
        val proxy = Proxy.newProxyInstance(
            FakeRequest::class.java.classLoader,
            arrayOf(HttpServletRequest::class.java),
            handler,
        ) as HttpServletRequest
        handler.self = proxy
        proxy
    }
}

/** A fake [HttpServletResponse] that only reports its status. */
internal fun fakeResponse(status: Int): HttpServletResponse {
    val handler = InvocationHandler { proxy, m, args ->
        when (m.name) {
            "getStatus" -> status
            "toString" -> "FakeHttpServletResponse($status)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args!![0]
            else -> throw UnsupportedOperationException("FakeResponse: ${m.name}")
        }
    }
    return Proxy.newProxyInstance(
        FakeRequest::class.java.classLoader,
        arrayOf(HttpServletResponse::class.java),
        handler,
    ) as HttpServletResponse
}
