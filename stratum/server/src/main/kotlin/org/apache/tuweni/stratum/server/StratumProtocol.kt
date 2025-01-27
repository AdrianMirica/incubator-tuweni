/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tuweni.stratum.server

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.json.JsonMapper
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Instant
import java.util.ArrayList
import java.util.Random

/**
 * Handler capable of taking care of a connection to a Stratum server according to a specific flavor of the Stratum protocol.
 */
interface StratumProtocol {

  /**
   * Checks if the protocol can handle a TCP connection, based on the initial message.
   *
   * @param initialMessage the initial message sent over the TCP connection.
   * @param conn the connection itself
   * @return true if the protocol can handle this connection
   */
  fun canHandle(initialMessage: String, conn: StratumConnection): Boolean

  /**
   * Callback when a stratum connection is closed.
   *
   * @param conn the connection that just closed
   */
  fun onClose(conn: StratumConnection)

  /**
   * Handle a message over an established Stratum connection
   *
   * @param conn the Stratum connection
   * @param message the message to handle
   */
  fun handle(conn: StratumConnection, message: String)

  /**
   * Sets the current proof-of-work job.
   *
   * @param input the new proof-of-work job to send to miners
   */
  fun setCurrentWorkTask(input: PoWInput)
}

/**
 * Implementation of the stratum+tcp protocol.
 *
 *
 * This protocol allows miners to submit EthHash solutions over a persistent TCP connection.
 */
class Stratum1Protocol(
  private val extranonce: String,
  private val jobIdSupplier: () -> String = {
    val timeValue: Bytes =
      Bytes.minimalBytes(Instant.now().toEpochMilli())
    timeValue.slice(timeValue.size() - 4, 4).toShortHexString()
  },
  private val subscriptionIdCreator: () -> String = { createSubscriptionID() },
  private val submitCallback: (PoWSolution) -> (Boolean),
  private val seedSupplier: () -> Bytes32,
) : StratumProtocol {
  private var currentInput: PoWInput? = null
  private val activeConnections: MutableList<StratumConnection> = ArrayList()

  companion object {
    private val logger = LoggerFactory.getLogger(Stratum1Protocol::class.java)
    private val mapper = JsonMapper()
    private val STRATUM_1 = "EthereumStratum/1.0.0"
    private fun createSubscriptionID(): String {
      val subscriptionBytes = ByteArray(16)
      Random().nextBytes(subscriptionBytes)
      return Bytes.wrap(subscriptionBytes).toShortHexString()
    }
  }

  override fun canHandle(initialMessage: String, conn: StratumConnection): Boolean {
    if (!initialMessage.contains("mining.subscribe")) {
      logger.debug("Invalid first message method: {}", initialMessage)
      return false
    }
    val requestBody: JsonRpcRequest
    try {
      requestBody = mapper.readValue(initialMessage, JsonRpcRequest::class.java)
    } catch (e: JsonProcessingException) {
      logger.debug(e.message, e)
      return false
    }

    try {
      val notify = mapper.writeValueAsString(
        JsonRpcSuccessResponse(
          id = requestBody.id,
          result = mutableListOf(
            mutableListOf(
              "mining.notify",
              subscriptionIdCreator(),
              STRATUM_1
            ),
            extranonce
          )
        )
      )
      conn.send(notify + "\n")
    } catch (e: JsonProcessingException) {
      logger.debug(e.message, e)
      conn.close()
    }
    return true
  }

  private fun registerConnection(conn: StratumConnection) {
    activeConnections.add(conn)
    if (currentInput != null) {
      sendNewWork(conn)
    }
  }

  private fun sendNewWork(conn: StratumConnection) {
    val input = currentInput ?: return
    val params = mutableListOf<Any>(
      jobIdSupplier(),
      Bytes.wrap(input.prePowHash).toHexString(),
      seedSupplier().toHexString(),
      input.target.toBytes().toHexString(),
      true
    )
    val req = JsonRpcRequest("2.0", "mining.notify", params, "32")
    try {
      conn.send(mapper.writeValueAsString(req) + "\n")
    } catch (e: JsonProcessingException) {
      logger.debug(e.message, e)
    }
  }

  override fun onClose(conn: StratumConnection) {
    activeConnections.remove(conn)
  }

  override fun handle(conn: StratumConnection, message: String) {
    try {
      val req: JsonRpcRequest = mapper.readValue(message, JsonRpcRequest::class.java)
      if ("mining.authorize" == req.method) {
        handleMiningAuthorize(conn, req)
      } else if ("mining.submit" == req.method) {
        handleMiningSubmit(conn, req)
      }
    } catch (e: JsonProcessingException) {
      logger.debug(e.message, e)
      conn.close()
    } catch (e: IOException) {
      logger.debug(e.message, e)
      conn.close()
    }
  }

  private fun handleMiningSubmit(conn: StratumConnection, message: JsonRpcRequest) {
    logger.debug("Miner submitted solution {}", message)
    val solution = PoWSolution(
      message.bytes(2).getLong(0),
      message.bytes32(4),
      null,
      message.bytes(3)
    )
    currentInput?.prePowHash?.equals(solution.powHash)?.let {
      if (it) {
        val result = submitCallback(solution)
        val response = mapper.writeValueAsString(JsonRpcSuccessResponse(message.id, result = result))
        conn.send(response + "\n")
      }
    }
  }

  @Throws(IOException::class)
  private fun handleMiningAuthorize(conn: StratumConnection, message: JsonRpcRequest) {
    // discard message contents as we don't care for username/password.
    // send confirmation
    val confirm = mapper.writeValueAsString(JsonRpcSuccessResponse(message.id, result = true))
    conn.send(confirm + "\n")
    // ready for work.
    registerConnection(conn)
  }

  override fun setCurrentWorkTask(input: PoWInput) {
    currentInput = input
    logger.debug("Sending new work to miners: {}", input)
    for (conn: StratumConnection in activeConnections) {
      sendNewWork(conn)
    }
  }
}

/**
 * Implementation of the stratum1+tcp protocol.
 *
 *
 * This protocol allows miners to submit EthHash solutions over a persistent TCP connection.
 */
class Stratum1EthProxyProtocol(
  private val submitCallback: (PoWSolution) -> Boolean,
  private val seedSupplier: () -> Bytes32,
  private val hashrateCallback: (Bytes, Long) -> Boolean,
) : StratumProtocol {

  companion object {
    private val logger = LoggerFactory.getLogger(Stratum1EthProxyProtocol::class.java)
    private val mapper = JsonMapper()
  }

  private var currentInput: PoWInput? = null

  override fun canHandle(initialMessage: String, conn: StratumConnection): Boolean {
    try {
      val req = mapper.readValue(initialMessage, JsonRpcRequest::class.java)
      if (req.method != "eth_submitLogin") {
        logger.debug("Invalid first message method: {}", initialMessage)
        return false
      }
      val response = mapper.writeValueAsString(JsonRpcSuccessResponse(req.id, result = true))
      conn.send(response + "\n")
    } catch (e: JsonProcessingException) {
      logger.debug(e.message, e)
      conn.close()
      return false
    }
    return true
  }

  private fun sendNewWork(conn: StratumConnection, id: String) {
    val input = currentInput ?: return
    val result = mutableListOf(
      input.prePowHash.toHexString(),
      seedSupplier(),
      input.target.toHexString()
    )
    val req = JsonRpcSuccessResponse(id = id, result = result)
    try {
      conn.send(mapper.writeValueAsString(req) + "\n")
    } catch (e: JsonProcessingException) {
      logger.debug(e.message, e)
    }
  }

  override fun onClose(conn: StratumConnection) {}

  override fun handle(conn: StratumConnection, message: String) {
    try {
      val req: JsonRpcRequest = mapper.readValue(message, JsonRpcRequest::class.java)
      if ("eth_getWork" == req.method) {
        sendNewWork(conn, req.id)
      } else if ("eth_submitWork" == req.method) {
        handleMiningSubmit(conn, req)
      } else if ("eth_submitHashrate" == req.method) {
        handleHashrateSubmit(mapper, conn, req)
      } else {
        logger.debug("Unsupported message: {}", req.method)
      }
    } catch (e: JsonProcessingException) {
      logger.debug(e.message, e)
      conn.close()
    } catch (e: IOException) {
      logger.debug(e.message, e)
      conn.close()
    }
  }

  @Throws(IOException::class)
  private fun handleMiningSubmit(conn: StratumConnection, req: JsonRpcRequest) {
    logger.debug("Miner submitted solution {}", req)
    val solution = PoWSolution(
      req.bytes(0).getLong(0),
      req.bytes32(2),
      null,
      req.bytes(1)
    )
    currentInput?.prePowHash?.equals(solution.powHash)?.let {
      if (it) {
        val result = submitCallback(solution)
        val response = mapper.writeValueAsString(JsonRpcSuccessResponse(id = req.id, result = result))
        conn.send(response + "\n")
      }
    }
  }

  override fun setCurrentWorkTask(input: PoWInput) {
    currentInput = input
  }

  @Throws(IOException::class)
  fun handleHashrateSubmit(
    mapper: JsonMapper,
    conn: StratumConnection,
    message: JsonRpcRequest
  ) {
    val hashRate = message.bytes(0)
    val id = message.bytes(1)
    val response = mapper.writeValueAsString(
      JsonRpcSuccessResponse(
        message.id,
        result = hashrateCallback(id, hashRate.toBigInteger().toLong())
      )
    )
    conn.send(response + "\n")
  }
}
