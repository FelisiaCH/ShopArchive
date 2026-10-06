package xyz.felismp.shoparchive.launcher.hotfix

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

const val TARGET_CLASS = "hotfixfixtures.Target"
const val TARGET_INTERNAL = "hotfixfixtures/Target"
const val GREET_DESC = "(Ljava/lang/String;)Ljava/lang/String;"

fun targetBytes(): ByteArray = Target::class.java.getResourceAsStream("/$TARGET_INTERNAL.class")!!.readBytes()

private typealias Target = hotfixfixtures.Target

/** One patch method of a generated hotfix class: replaces [method] and returns the constant "fixed" (the target methods used return String or Long). */
class FixSpec(
    val method: String = "greet",
    /** The descriptor in @HotfixPatch. */
    val targetDescriptor: String = GREET_DESC,
    val sha: String = sha256(targetBytes()),
    val replacementName: String = "greet",
    /** The replacement's own descriptor: the instance is the first parameter. */
    val replacementDescriptor: String = "(L$TARGET_INTERNAL;Ljava/lang/String;)Ljava/lang/String;",
    val targetClass: String = TARGET_CLASS,
)

fun fixClass(internalName: String, vararg specs: FixSpec, asInterface: Boolean = false): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    val access = if (asInterface) Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER
    writer.visit(Opcodes.V17, access, internalName, null, "java/lang/Object", null)
    for (spec in specs) {
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, spec.replacementName, spec.replacementDescriptor, null, null)
        val annotation = mv.visitAnnotation(HOTFIX_PATCH_DESC, false)
        annotation.visit("targetClass", spec.targetClass)
        annotation.visit("method", spec.method)
        annotation.visit("descriptor", spec.targetDescriptor)
        annotation.visit("classSha256", spec.sha)
        annotation.visitEnd()
        mv.visitCode()
        when (Type.getReturnType(spec.replacementDescriptor).sort) {
            Type.LONG -> { mv.visitLdcInsn(777L); mv.visitInsn(Opcodes.LRETURN) }
            Type.INT -> { mv.visitIntInsn(Opcodes.BIPUSH, 7); mv.visitInsn(Opcodes.IRETURN) }
            else -> { mv.visitLdcInsn("fixed"); mv.visitInsn(Opcodes.ARETURN) }
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
}

fun jar(file: Path, entries: Map<String, ByteArray>, manifestClassPath: String? = null, manifestExtra: String = ""): File {
    Files.createDirectories(file.parent)
    val out = ByteArrayOutputStream()
    JarOutputStream(out).use { jar ->
        if (manifestClassPath != null || manifestExtra.isNotEmpty()) {
            jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jar.write(("Manifest-Version: 1.0\r\n" + (manifestClassPath?.let { "Class-Path: $it\r\n" } ?: "") + manifestExtra + "\r\n").toByteArray())
            jar.closeEntry()
        }
        for ((name, bytes) in entries) {
            jar.putNextEntry(JarEntry(name))
            jar.write(bytes)
            jar.closeEntry()
        }
    }
    Files.write(file, out.toByteArray())
    return file.toFile()
}

fun coreJar(dir: Path, build: Int = 5, fixed: String = ""): File =
    jar(dir.resolve("core.jar"), mapOf(
        "$TARGET_INTERNAL.class" to targetBytes(),
        "shoparchive-build.properties" to "build=$build\nfixed-issues=$fixed\n".toByteArray(),
    ))

fun pluginYml(name: String, id: String = "SA-1", severity: String = "security", build: Int = 5, extra: String = ""): ByteArray =
    "name: $name\nversion: 1.0\nhotfix: true\nfixes: [$id]\nseverity: $severity\ntarget-build: $build\n$extra".toByteArray()

/** A hotfix jar in `<root>/plugins/<name>.jar`; returns its SHA-256. */
fun hotfixJar(root: Path, name: String, vararg specs: FixSpec, yml: ByteArray = pluginYml(name), asInterface: Boolean = false): String {
    val file = jar(root.resolve("plugins/$name.jar"), mapOf("plugin.yml" to yml, "fix/Fix$name.class" to fixClass("fix/Fix$name", *specs, asInterface = asInterface)))
    return sha256(Files.readAllBytes(file.toPath()))
}

/** What the core's `plugins approve` writes, exactly (the server's own test pins the same text). */
fun approve(root: Path, vararg approved: Pair<String, String>) {
    val text = "# Plugin jars the admin approved: plugin name -> SHA-256 of the jar.\n" +
        "# Written by the console commands 'plugins approve' and 'plugins revoke'; a change applies at the next start.\n" +
        "approved:\n" + approved.sortedBy { it.first }.joinToString("") { "  \"${it.first}\": \"${it.second}\"\n" }
    Files.createDirectories(root.resolve("plugins"))
    Files.write(root.resolve("plugins/approved.yml"), text.toByteArray())
}
