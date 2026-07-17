import java.net.Socket
import java.io.DataOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "net.apocalypse"
version = "1.10.10"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    // update-note 명령어에서 GitHub API 응답(JSON)을 파싱하는 데 쓴다. Paper 서버가 이미 Gson을 내장하고
    // 있어서 실행 시점에는 이 의존성이 없어도 되므로(compileOnly), 우리 jar에 다시 포함하지 않는다.
    compileOnly("com.google.code.gson:gson:2.11.0")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-Xlint:deprecation")
    }

    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
    }

    build {
        dependsOn(shadowJar)
    }
}

val pluginsDir = File("C:/Users/USER/Documents/Plugin Test/plugins")
val serverDir  = File("C:/Users/USER/Documents/Plugin Test")
val fixedJarName = "Apocalypse-${project.version}.jar"

fun isServerRunning(): Boolean = try {
    Socket("localhost", 25565).use { true }
} catch (_: Exception) { false }

fun readServerProp(key: String, default: String): String =
    File(serverDir, "server.properties").takeIf { it.exists() }
        ?.readLines()?.firstOrNull { it.startsWith("$key=") }
        ?.removePrefix("$key=") ?: default

fun sendRcon(command: String) {
    val port     = readServerProp("rcon.port",     "25575").toIntOrNull() ?: 25575
    val password = readServerProp("rcon.password", "")
    val socket   = Socket("localhost", port)
    socket.soTimeout = 5000
    try {
        val out = DataOutputStream(socket.outputStream)
        val inp = DataInputStream(socket.inputStream)

        fun send(id: Int, type: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer
                .allocate(4 + 4 + 4 + bytes.size + 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(4 + 4 + bytes.size + 2); buf.putInt(id); buf.putInt(type)
            buf.put(bytes); buf.put(0); buf.put(0)
            out.write(buf.array()); out.flush()
        }

        fun recv(): Triple<Int, Int, String> {
            val lenBytes = ByteArray(4).also { inp.readFully(it) }
            val len  = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
            val data = ByteArray(len).also { inp.readFully(it) }
            val bb   = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val id   = bb.int; val type = bb.int
            val body = ByteArray(len - 10).also { bb.get(it) }
            return Triple(id, type, String(body, Charsets.UTF_8))
        }

        send(1, 3, password)
        val (authId, _, _) = recv()
        if (authId == -1) throw RuntimeException("RCON 인증 실패 (비밀번호 확인)")

        send(2, 2, command)
        val (_, _, response) = recv()
        if (response.isNotBlank()) println("[RCON] $response")
    } finally {
        socket.close()
    }
}

var serverWasRunning = false

tasks.register("disablePlugin") {
    doLast {
        if (!isServerRunning()) {
            println("[disablePlugin] 서버 꺼져있음, 건너뜀")
            return@doLast
        }
        if (readServerProp("enable-rcon", "false") != "true") {
            println("[disablePlugin] RCON 비활성화됨 — server.properties에 enable-rcon=true 필요")
            return@doLast
        }
        try {
            sendRcon("plugman unload Apocalypse")
            serverWasRunning = true
            println("[disablePlugin] 플러그인 언로드 완료")
        } catch (e: Exception) {
            println("[disablePlugin] 실패: ${e.message}")
        }
    }
}

tasks.register("enablePlugin") {
    doLast {
        if (!serverWasRunning) {
            println("[enablePlugin] 서버가 꺼져있었음, 건너뜀")
            return@doLast
        }
        try {
            sendRcon("plugman load Apocalypse")
            println("[enablePlugin] 플러그인 로드 완료")
        } catch (e: Exception) {
            println("[enablePlugin] 실패: ${e.message}")
        }
    }
}

tasks.register("copyToPlugins") {
    dependsOn(tasks.shadowJar)
    doLast {
        pluginsDir.mkdirs()
        val jarFile = tasks.shadowJar.get().archiveFile.get().asFile
        jarFile.copyTo(File(pluginsDir, fixedJarName), overwrite = true)
        println("[copyToPlugins] 복사 완료: $fixedJarName")
    }
}

tasks.register("deleteOldPlugin") {
    dependsOn("copyToPlugins")
    finalizedBy("enablePlugin")
    doLast {
        // .bak 잔여 파일 정리
        pluginsDir.listFiles { f -> f.name.endsWith(".jar.bak") }?.forEach { f ->
            if (f.delete()) println("[deleteOldPlugin] .bak 정리: ${f.name}")
        }
        // 이번에 배포한 버전(fixedJarName) 외의 구버전 jar 삭제
        val targets = pluginsDir.listFiles { f ->
            f.name.startsWith("Apocalypse") && f.name.endsWith(".jar") && f.name != fixedJarName
        } ?: emptyArray()
        if (targets.isEmpty()) {
            println("[deleteOldPlugin] 삭제할 구버전 없음")
        } else {
            targets.forEach { f ->
                if (f.delete()) {
                    println("[deleteOldPlugin] 삭제: ${f.name}")
                } else {
                    val bak = File(f.parent, f.name + ".bak")
                    if (f.renameTo(bak)) {
                        println("[deleteOldPlugin] 이름 변경: ${f.name} -> ${bak.name}")
                    } else {
                        println("[deleteOldPlugin] 삭제 실패: ${f.name}")
                    }
                }
            }
        }
    }
}

tasks.build {
    finalizedBy("deleteOldPlugin")
}

tasks.named("compileJava") {
    dependsOn("disablePlugin")
}
