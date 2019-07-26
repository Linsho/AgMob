package club.itsp.elang.agmob.workspace

import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.*
import io.ktor.request.receiveText
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.WebSocketServerSession
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.collections.HashMap

@Serializable
data class SessionConfiguration(val interval: Int)

@Serializable
class Session(val config: SessionConfiguration) {
    val id = UUID.randomUUID().toString()
    @Transient
    var driver: DriverConnection? = null
        private set
    @Transient
    val navigators = HashMap<Int, NavigatorConnection>()

    fun addNavigator(conn: NavigatorConnection) {
        navigators[conn.id] = conn
    }

    fun setDriver(conn: DriverConnection) {
        driver = conn
    }
}

abstract class BaseConnection(val session: Session) {
    val id = idBase++

    companion object {
        private var idBase = 0
    }
}

class DriverConnection(session: Session, private val wsSession: WebSocketServerSession) : BaseConnection(session) {
    suspend fun requestSdpOffer(navConn: NavigatorConnection, message: WebSocketMessage) {
        wsSession.send(WebSocketMessage("request_sdp", message.payload, navConn.id).toJson())
    }

    suspend fun receiveSdpAnswer(navConn: NavigatorConnection, message: WebSocketMessage) {
        wsSession.send(WebSocketMessage("sdp", message.payload, navConn.id).toJson())
    }
}

class NavigatorConnection(session: Session, private val wsSession: WebSocketServerSession) : BaseConnection(session) {
    suspend fun receiveSdpOffer(message: WebSocketMessage) {
        wsSession.send(WebSocketMessage("sdp", message.payload).toJson())
    }
}

// FIXME: navigator_id smells bad
@Serializable
data class WebSocketMessage(val kind: String, val payload: String, val navigator_id: Int = -1) {
    fun toJson(): String = Json.stringify(serializer(), this)

    companion object {
        fun parseJson(data: String) = Json.parse(serializer(), data)
    }
}

fun main(args: Array<String>) {
    val sessions = HashMap<String, Session>()

    embeddedServer(Netty, 8080) {
        install(WebSockets)

        routing {
            post("/api/session") {
                val configText = call.receiveText()
                // FIXME: Once driver supports it, this 'if' must be removed
                val sess = Session(if (configText.isNotBlank())
                    Json.parse(SessionConfiguration.serializer(), configText)
                else
                    SessionConfiguration(10 * 60))
                sessions[sess.id] = sess
                call.respondText(Json.stringify(Session.serializer(), sess), ContentType.Application.Json)
            }

            get("/api/session/{id}") {
                val sess = sessions[call.parameters["id"]]
                if (sess == null) {
                    call.respondText("FIXME: invalid sess id", status = HttpStatusCode.BadRequest)
                    return@get
                }
                call.respondText(Json.stringify(Session.serializer(), sess), ContentType.Application.Json)
            }

            webSocket("/api/session/{id}/navigator") {
                val sess = sessions[call.parameters["id"]]
                if (sess == null) {
                    call.respondText("FIXME: invalid sess id", status = HttpStatusCode.BadRequest)
                    return@webSocket
                }
                val conn = NavigatorConnection(sess, this)
                sess.addNavigator(conn)

                for (frame in incoming) {
                    if (frame !is Frame.Text) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "FIXME: invalid frame"))
                        continue
                    }
                    val msg = WebSocketMessage.parseJson(frame.readText())
                    when (msg.kind) {
                        "request_sdp" -> {
                            val driver = sess.driver
                            driver?.requestSdpOffer(conn, msg)
                        }
                        "sdp" -> {
                            val driver = sess.driver
                            driver?.receiveSdpAnswer(conn, msg)
                        }
                        else -> {
                            log.info("invalid websocket message from navigator")
                        }
                    }
                }
            }

            webSocket("/api/session/{id}/driver") {
                val sess = sessions[call.parameters["id"]]
                if (sess == null) {
                    call.respondText("FIXME: invalid sess id", status = HttpStatusCode.BadRequest)
                    return@webSocket
                }
                val conn = DriverConnection(sess, this)
                sess.setDriver(conn)

                for (frame in incoming) {
                    if (frame !is Frame.Text) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "FIXME: invalid frame"))
                        continue
                    }
                    val msg = WebSocketMessage.parseJson(frame.readText())
                    when (msg.kind) {
                        "sdp" -> {
                            val navConn = sess.navigators[msg.navigator_id]
                            navConn?.receiveSdpOffer(msg)
                        }
                        else -> {
                            log.info("invalid websocket message from navigator")
                        }
                    }
                }
            }
        }
    }.start(wait = true)
}
