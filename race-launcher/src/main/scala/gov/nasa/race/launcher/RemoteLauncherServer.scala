/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.race.launcher

import java.io._
import java.net.{ServerSocket, Socket}
import java.security.Key
import java.util
import java.util.concurrent.{ExecutorService, Executors}

import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.ThreadUtils._
import gov.nasa.race._
import gov.nasa.race.util.{ConsoleIO, _}

import scala.collection.Seq

/**
  * command line options for RemoteLauncherServer (scopt based parser, but mutable options)
  */
class ServerLauncherOpts extends SSLLauncherOpts("launch-server") {
  var poolSize = 5

  opt1("--poolsize")("<number>",s"thread pool size for concurrent requests (default=$poolSize)"){a=> poolSize=parseInt(a)}

  override def identityInit = Some(new File(identityDir,"id_rsa"))

  override def show = {
    super.show
    println(s"  poolSize:     $poolSize")
  }
}

/**
  * policy data specifying where and what we run remotely
  */
case class RemoteSpec (remoteUser: String, // user id on remote host
                       remoteHost: String, // host we are launching on
                       remoteId: File,     // ssh private key on remote host (for login-less users)
                       remoteCmd: String   // command we launch on remote host
                      )

/**
  * the generic launcher server app
  * Since the concrete RemoteLauncherServer object encapsulates resource management policy, its
  * class can be specified with a 'race.launcher.class' system property
  */
object RemoteLauncherServer extends App {
  ClassLoaderUtils.initializeCodeSourceMap(RemoteLauncher.codeSourceEntries)

  val launcherClsName = System.getProperty("race.remote.launcher")
  if (launcherClsName != null){
    try {
     ClassLoaderUtils.newInstanceOf[RemoteLauncherServer](Class.forName(launcherClsName)).run(args)
    } catch {
      case x: ClassNotFoundException => ConsoleIO.printlnErr(s"launcher class not found: $launcherClsName")
      case x: ClassCastException => ConsoleIO.printlnErr(s"not a RemoteLauncherServer: $launcherClsName")
    }
  } else {
    new RemoteLauncherServer().run(args)
  }
}


/**
  * a server that is located within a trusted network, accepting simulation session requests from trusted clients
  * (such as RemoteLauncherClient) via SSL, launching and monitoring RemoteMain processes via SSH
  *
  * Since this server is not internet-facing, it can assume the respective front end already
  * authenticated the requesting user. Not that this user id is NOT the one we use for launching
  * RemoteMain via SSH - it is the job of the RemoteLaunchServer to map this to (preferably non-login)
  * user accounts on the machines that run RemoteMain
  *
  * We provide a state-less interface towards the clients (e.g. RemoteLauncherClient), i.e. session
  * state is completely encapsulated within RemoteLauncherServer
  */
class RemoteLauncherServer extends RemoteLauncher {

  override val opts: ServerLauncherOpts =  new ServerLauncherOpts
  protected[this] var vaultKeyResolver: (String,File)=>Option[Key] = (s,f) => None

  /** main entry method for RemoteLauncherServers, which can be overridden */
  def run (args: Array[String]): Unit = {
    if (opts.parse(args)){
      opts.show

      for (
        pw <- ConsoleIO.promptPassword(s"enter password for keystore ${opts.keyStore}: ");
        ksFile <- opts.keyStore;
        ks <- CryptUtils.loadKeyStore(ksFile,pw);
        kmap <- CryptUtils.getKeyMap(ks,pw);
        kmf <- tryFinally(NetUtils.keyManagerFactory(ks,pw)){util.Arrays.fill(pw,' ')};  // done with pw - erase content
        sslContext <- trySome(NetUtils.sslContext(kmf,NetUtils.trustAllCerts));
        serverSocket <- trySome(NetUtils.sslServerSocket(sslContext,opts.requestPort))
      ) {
        val pool = Executors.newFixedThreadPool(opts.poolSize)
        vaultKeyResolver = (remoteUser,vaultFile) => resolveVaultKey(kmap,remoteUser,vaultFile)
        addShutdownHook {
          serverSocket.close()
          pool.shutdown()
        }
        initializeWithOpts
        initLogging
        startMonitoringSessions

        listen(serverSocket,pool) // this is where we loop
      }
    }
  }

  def listen (serverSocket: ServerSocket, pool: ExecutorService): Unit = {
    println(s"server listening on port ${serverSocket.getLocalPort}, terminate with Ctrl-C ...")
    loopForeverWithExceptionLimit(5) {
      val socket = serverSocket.accept // this blocks until we have an incoming request
      pool.execute(asRunnable { processRequest(socket) }) // process it outside this thread so that we stay responsive
    }
  }

  /** NOTE this gets executed in a pooled thread */
  def processRequest (socket: Socket) = {
    for (
      reader <- NetUtils.createReader(socket);
      writer <- NetUtils.createWriter(socket)
    ) {
      tryWithSome(NetUtils.readNext(reader)) { request =>
        println(s"-- received request: $request")
        ConfigFactory.parseString(request)
      } match {
        case Some(request) =>
          request.withConfigForPath("launch"){ launch(_,writer) }
          request.withConfigForPath("list"){ listAllSessions(_,writer) }
          request.withConfigForPath("inspect"){ inspectSession(_,writer) }
          request.withConfigForPath("terminate"){ terminateSession(_,writer) }
          request.withConfigForPath("terminate-all"){ terminateAllSessions(_,writer) }
        case None => println("failure:\"malformed server request\"")
      }

      writer.flush
      socket.close
    }
  }

  def tryConfig (writer: Writer)(f: =>Unit) = {
    try { f } catch {
      case x: ConfigException.Missing => writer.write(s"""launch failure:"missing key ${x.getMessage}"""")
      case x: ConfigException.WrongType => writer.write(s"""launch failure:"wrong type ${x.getMessage}"""")
    }
  }

  /** execute a command on a remote host */
  def launch (request: Config, writer: Writer) = tryConfig(writer) {
    val requestUser = request.getString("user")
    val configPath = request.getString("config")
    val optVaultPath = request.getOptionalString("vault")
    val label = request.getString("label")

    for (
      RemoteSpec(user,host,idFile,cms) <- getRemoteSpec(requestUser,configPath);
      configFile <- FileUtils.existingNonEmptyFile(configPath)
    ) {
      val vd = optVaultPath.flatMap(getVaultData(user,_))
      val sid = s"$requestUser-$incSessionCount"
      val configs = getUniverseConfigs(Seq(configFile), opts.logLevel)

      startSession(sid, user, host, Some(idFile), label, new LaunchConfigSpec(configs,vd)) match {
        case Some(session) => writer.write(s"""session: {sid:"${session.sid}"}""")
        case None => writer.write(s"""failed: {sid:"$sid"} """)
      }
    }
  }

  /**
    * this is the main extension point of RemoteLauncherServer, which represents the resource
    * management policy to map (user,config) launch requests to RemoteSpecs.
    * The default just uses the command line option values
    * Note that we do require a ssh identity since the server runs non-interactive, i.e. there is
    * no way to enter a password for the remote machine
    */
  def getRemoteSpec(requestUser: String, configPath: String): Option[RemoteSpec] = {
    opts.identity.map(RemoteSpec(opts.user,opts.host,_,opts.remoteCmd))
  }

  /**
    * this maps vault pathnames to respective keys stored in our keystore, and
    * serializes the first-pass decrypted vault config together with the key
    * that is used to decrypt the vault config values.
    */
  def getVaultData(remoteUser: String, vaultPath: String): Option[Array[Byte]] = {
    try {
      for (
        file <- FileUtils.existingNonEmptyFile(vaultPath);
        key <- vaultKeyResolver(remoteUser,file);
        cipher <- CryptUtils.getDecryptionCipher(key);
        config <- CryptUtils.processFile2Config(file,cipher)
      ) yield serialize((config,key.getEncoded))
    } catch {
      case t: Throwable =>
        error(s"config vault processing failed with: $t")
        None
    }
  }

  /**
    * get a key to encrypt the vault data. The generic implementation does not discern between
    * remote users
    */
  def resolveVaultKey (keyMap: Map[String,Key], remoteUser: String, vault: File): Option[Key] = {
    keyMap.get(vault.getPath).           // try full pathname first
      orElse( keyMap.get(vault.getName). // next is just filename
      orElse( keyMap.get("vault")))      // generic fallback
  }

  def listAllSessions(request: Config, writer: Writer) = tryConfig(writer){
    val requestUserPrefix = request.getString("user") + '-'
    var i=0
    val response = liveSessions.foldLeft(new StringBuilder("sessions: [")){ (sb,e)=>
      val (key,session) = e
      if (key.startsWith(requestUserPrefix)) {
        i += 1
        if (i > 1) sb.append(',')
        sb.append(s"""\n  session:{ id:"$key", label:"${session.label}" }""")
      } else sb
    }.append("\n]")
    writer.write(response.toString)
  }

  def inspectSession (request: Config, writer: Writer) = tryConfig(writer){
    val key = request.getString("sid")
    liveSessions.get(key) match {
      case Some(session) =>
        writer.write(s"""session: {sid:"${session.sid}", label:"${session.label}", started:"${session.startDate}"}""")
      case None =>
        writer.write(s"""failure: {reason:"no such session"}""")
    }
  }

  def terminateSession (request: Config, writer: Writer) = tryConfig(writer){
    val key = request.getString("sid")
    liveSessions.get(key) match {
      case Some(session) =>
        session.sendTerminateCtrlMsg
        writer.write(s"""terminated: {sid: "$key"}""")
      case None =>
        writer.write(s"""failure: {reason:"no such session"}""")
    }
  }

  def terminateAllSessions (request: Config, writer: Writer) = tryConfig(writer){
    val requestUserPrefix = request.getString("user") + '-'
    var i = 0
    val response = liveSessions.foldLeft(new StringBuilder("terminated: [")){ (sb,e)=>
      val (key,session) = e
      if (key.startsWith(requestUserPrefix)) {
        session.sendTerminateCtrlMsg
        i += 1
        if (i > 1) sb.append(',')
        sb.append(s"""\n  session:{ id:"$key", label:"${session.label}" }""")
      } else sb
    }.append("\n]")
    writer.write(response.toString)
  }
}
