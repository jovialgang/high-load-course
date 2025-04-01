package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.math.pow


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName

    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    //    private val client = OkHttpClient.Builder().build()
    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofMillis(1300L))
        .build()

    private val ratelim = SlidingWindowRateLimiter(
        rate = rateLimitPerSec.toLong(),
        window = Duration.ofSeconds(1)
    )

    private val executor: ExecutorService = Executors.newFixedThreadPool(parallelRequests)

    private val ongoingWindow = OngoingWindow(parallelRequests)

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()
        logger.info("[$accountName] Submit for $paymentId, txId: $transactionId")

        try {
            // Логируем отправку платежа
            paymentESService.update(paymentId) {
                it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
            }

            ratelim.tickBlocking()

            val request = Request.Builder()
                .url("http://localhost:1234/external/process?serviceName=$serviceName&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
                .post(emptyBody)
                .build()

            var responseBody: ExternalSysResponse? = null
            var attempt = 0
            var success = false

            executor.submit {
                while (attempt < 3 && !success) {
                    ongoingWindow.acquire()
                    client.newCall(request).execute().use { response ->
                        val responseStr = response.body?.string()

                        responseBody = try {
                            mapper.readValue(responseStr, ExternalSysResponse::class.java)
                        } catch (e: Exception) {
                            logger.error("[$accountName] Ошибка при разборе ответа, код: ${response.code}, тело: $responseStr")
                            ExternalSysResponse(
                                transactionId.toString(),
                                paymentId.toString(),
                                false,
                                "Ошибка парсинга JSON"
                            )
                        }

                        success = responseBody!!.result

                        // Если неуспешно и код позволяет ретрай, делаем экспоненциальную задержку
                        if (!success && response.code in listOf(429, 500, 502, 503, 504)) {
                            attempt++
                            if (attempt < 3) {
                                val delay = (100L * 2.0.pow(attempt)).toLong()
                                logger.warn("[$accountName] Повторная попытка $attempt для $paymentId (код: ${response.code}), задержка: $delay мс")
                                Thread.sleep(delay)
                            }
                        }
                    }
                }
            }

            // Логируем результат
            logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${responseBody?.result}, message: ${responseBody?.message}")

            paymentESService.update(paymentId) {
                it.logProcessing(responseBody?.result ?: false, now(), transactionId, reason = responseBody?.message)
            }
        } catch (e: Exception) {
            logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = e.message)
            }
        } finally {
            ongoingWindow.release()
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()