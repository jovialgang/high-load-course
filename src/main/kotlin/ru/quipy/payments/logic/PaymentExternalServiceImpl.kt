package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.io.IOException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
//import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        private val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        private val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec

    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(5))
        .executor(Executors.newVirtualThreadPerTaskExecutor()) // ⚡ виртуальные потоки
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()
        logger.info("[$accountName] Submit for $paymentId, txId: $transactionId")

        val uri = URI.create(
            "http://localhost:1234/external/process?serviceName=$serviceName&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
        )

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofMillis(requestAverageProcessingTime.toMillis() * 2))
            .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                val responseBody = try {
                    mapper.readValue(response.body(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    logger.error("Ошибка при парсинге JSON: ${response.body()}")
                    ExternalSysResponse(
                        transactionId.toString(),
                        paymentId.toString(),
                        false,
                        "Ошибка парсинга"
                    )
                }

                paymentESService.update(paymentId) {
                    it.logProcessing(responseBody.result, now(), transactionId, responseBody.message)
                }

                logger.info("[$accountName] Payment response: ${responseBody.result}, message: ${responseBody.message}")
            }
            .exceptionally { ex ->
                logger.error("Ошибка при отправке запроса на оплату $paymentId", ex)
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, ex.message)
                }
                null
            }
    }

    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = properties.accountName
}

fun now() = System.currentTimeMillis()
