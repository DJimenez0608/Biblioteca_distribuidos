plugins {
    id("java")
    id("application")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.zeromq:jeromq:0.5.3")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
}

tasks.test {
    useJUnitPlatform()
}

application {
    val targetMain = project.findProperty("mainClass") as String?
    mainClass.set(targetMain ?: "org.example.GA")
}

fun registerRunTask(taskName: String, mainClassName: String, descriptionText: String) =
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = descriptionText
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainClassName)
        standardInput = System.`in`
    }

registerRunTask("runGA", "org.example.GA", "Ejecuta el Gestor de Almacenamiento")
registerRunTask("runGC", "org.example.GC", "Ejecuta el Gestor de Carga")
registerRunTask("runActorPrestamo", "org.example.AcotrPresamo", "Ejecuta el actor encargado de préstamos")
registerRunTask("runActorDevolucion", "org.example.ActorDevolucion", "Ejecuta el actor de devoluciones")
registerRunTask("runActorRenovacion", "org.example.ActorRenovacion", "Ejecuta el actor de renovaciones")
registerRunTask("runPS", "org.example.PS", "Ejecuta un Proceso Solicitante")