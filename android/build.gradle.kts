import org.gradle.api.Action

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

fun pinCompileSdk36(target: Any) {
    val setter = target.javaClass.methods.firstOrNull { method ->
        method.name == "setCompileSdk" &&
            method.parameterCount == 1 &&
            (method.parameterTypes[0] == Integer.TYPE || method.parameterTypes[0] == Integer::class.java)
    }
    setter?.invoke(target, 36)
}

fun org.gradle.api.Project.pinAndroidCompileSdk36() {
    listOf("com.android.library", "com.android.application").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            val components = extensions.findByName("androidComponents") ?: return@withPlugin
            val finalize = components.javaClass.methods.firstOrNull { method ->
                method.name == "finalizeDsl" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0].name == "org.gradle.api.Action"
            } ?: return@withPlugin
            finalize.invoke(
                components,
                object : Action<Any> {
                    override fun execute(t: Any) {
                        pinCompileSdk36(t)
                    }
                },
            )
        }
    }
}

// Must be registered before evaluationDependsOn(":app"), otherwise plugin projects are
// already evaluated and afterEvaluate throws (known failure). finalizeDsl runs after the
// plugin android block and before compileSdk is locked.
subprojects {
    pinAndroidCompileSdk36()
}

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
