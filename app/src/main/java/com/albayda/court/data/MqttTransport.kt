package com.albayda.court.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.util.UUID

/**
 * Shared event database without any manual setup.
 *
 * Every event is published as a RETAINED MQTT message on its own topic
 * (albayda/court/event/<event-id>). The public broker keeps one retained
 * message per topic, so a device that connects later receives the complete
 * history and every device sees the same data — effectively one shared DB.
 */
object MqttTransport {
    private const val TOPIC_PREFIX = "albayda/court/event/"
    private const val TOPIC_WILDCARD = "albayda/court/event/#"
    private val brokers = listOf(
        "ssl://broker.hivemq.com:8883",
        "tcp://broker.hivemq.com:1883"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var client: IMqttAsyncClient? = null
    private var eventCallback: ((String) -> Unit)? = null
    private var readyCallback: (() -> Unit)? = null
    private var stateCallback: ((String) -> Unit)? = null
    private var started = false
    private var attempt = 0

    @Volatile
    var lastState: String = "جارٍ الاتصال..."

    private val clientId: String by lazy {
        val prefs = appContext!!.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("mqtt_client_id", "") ?: ""
        if (id.isBlank()) {
            id = "albayda-" + UUID.randomUUID().toString().replace("-", "").take(18)
            prefs.edit().putString("mqtt_client_id", id).apply()
        }
        id
    }

    fun start(
        context: Context,
        onEvent: (String) -> Unit,
        onReady: () -> Unit,
        onState: (String) -> Unit
    ) {
        appContext = context.applicationContext
        eventCallback = onEvent
        readyCallback = onReady
        stateCallback = onState
        if (started) return
        started = true
        connect()
    }

    private fun setState(value: String) {
        lastState = value
        stateCallback?.invoke(value)
    }

    private fun connect() {
        val uri = brokers[attempt % brokers.size]
        try {
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 60
                isAutomaticReconnect = true
            }
            val c = MqttAsyncClient(uri, clientId, null)
            client = c
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    attempt = 0
                    setState("متصل ✓")
                    c.subscribe(
                        arrayOf(TOPIC_WILDCARD),
                        intArrayOf(1),
                        null,
                        object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                readyCallback?.invoke()
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                setState("تعذر الاشتراك")
                            }
                        }
                    )
                }

                override fun connectionLost(cause: Throwable?) {
                    setState("جارٍ إعادة الاتصال...")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload ?: return
                    try {
                        eventCallback?.invoke(String(payload, Charsets.UTF_8))
                    } catch (_: Exception) {
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            c.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    scheduleRetry("تعذر الاتصال — إعادة المحاولة")
                }
            })
        } catch (_: Exception) {
            scheduleRetry("تعذر الاتصال — إعادة المحاولة")
        }
    }

    private fun scheduleRetry(message: String) {
        setState(message)
        attempt++
        val delay = if (attempt >= brokers.size) {
            attempt = 0
            10_000L
        } else {
            1_000L
        }
        handler.postDelayed({ connect() }, delay)
    }

    fun publish(id: String, type: String, payload: String, createdAt: String) {
        val c = client
        if (c == null || !c.isConnected) return
        try {
            val json = JSONObject()
                .put("id", id)
                .put("type", type)
                .put("payload", JSONObject(payload))
                .put("created_at", createdAt)
                .toString()
            val message = MqttMessage(json.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            c.publish(TOPIC_PREFIX + id, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {}
            })
        } catch (_: Exception) {
        }
    }
}
