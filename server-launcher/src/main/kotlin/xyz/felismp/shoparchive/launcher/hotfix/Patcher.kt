package xyz.felismp.shoparchive.launcher.hotfix

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

internal const val HOTFIX_TARGET_DESC = "Lxyz/felismp/shoparchive/api/hotfix/HotfixTarget;"
internal const val HOTFIX_PATCH_DESC = "Lxyz/felismp/shoparchive/api/hotfix/HotfixPatch;"

/** One patch as a hotfix jar declares it with `@HotfixPatch` on a public static method. */
internal class PatchSpec(
    /** Binary name of the target class, with dots. */
    val targetClass: String,
    val method: String,
    val descriptor: String,
    val classSha256: String,
    /** Internal name of the hotfix class holding the replacement, and the replacement itself. */
    val replacementOwner: String,
    val replacementName: String,
    val replacementDescriptor: String,
    /** The owner is an interface: the call to a static method of it must be emitted as an interface method reference. */
    val replacementOwnerIsInterface: Boolean = false,
) {
    val targetKey: String get() = "$targetClass#$method$descriptor"
    val replacementText: String get() = "${replacementOwner.replace('/', '.')}#$replacementName"
}

/** A patch that cannot be made; [message] says why. */
internal class PatchException(message: String) : Exception(message)

/** The `@HotfixPatch` methods of one hotfix class, or the reason one of them is not usable. */
internal fun readPatchSpecs(classBytes: ByteArray): List<PatchSpec> {
    val node = readClass(classBytes)
    val specs = ArrayList<PatchSpec>()
    for (method in node.methods) {
        val annotation = annotationOf(method, HOTFIX_PATCH_DESC) ?: continue
        val where = "${node.name.replace('/', '.')}#${method.name}"
        val values = annotation.values ?: emptyList<Any?>()
        val map = HashMap<String, Any?>()
        var i = 0
        while (i + 1 < values.size) { map[values[i] as String] = values[i + 1]; i += 2 }
        fun text(key: String) = (map[key] as? String)?.takeIf { it.isNotBlank() } ?: throw PatchException("$where: @HotfixPatch has no $key")
        if (node.access and Opcodes.ACC_PUBLIC == 0) throw PatchException("$where: the class holding a patch must be public")
        if (method.access and Opcodes.ACC_PUBLIC == 0 || method.access and Opcodes.ACC_STATIC == 0) {
            throw PatchException("$where: a patch method must be public and static (in Kotlin: a top-level function, or @JvmStatic in an object)")
        }
        specs += PatchSpec(text("targetClass"), text("method"), text("descriptor"), text("classSha256").lowercase(), node.name, method.name, method.desc, node.access and Opcodes.ACC_INTERFACE != 0)
    }
    return specs
}

/**
 * [original] (the bytes of [targetClass]) with the bodies of the methods in [patches] replaced by a call to each patch's
 * replacement. Throws [PatchException] if a patch matches no method, the method is not marked `@HotfixTarget`, is of a
 * kind that cannot be patched safely, or the replacement's signature does not fit.
 */
internal fun applyPatches(original: ByteArray, targetClass: String, patches: List<PatchSpec>): ByteArray {
    val node = readClass(original)
    val internal = targetClass.replace('.', '/')
    for (patch in patches) {
        val matches = node.methods.filter { it.name == patch.method && it.desc == patch.descriptor }
        if (matches.size != 1) {
            throw PatchException("${patch.targetKey}: matched ${matches.size} method(s) in $targetClass, it must be exactly 1")
        }
        val target = matches.single()
        refuseUnsafe(target, patch)
        val isStatic = target.access and Opcodes.ACC_STATIC != 0
        val args = Type.getArgumentTypes(target.desc)
        val wantedParams = if (isStatic) args.toList() else listOf(Type.getObjectType(internal)) + args
        val wanted = Type.getMethodDescriptor(Type.getReturnType(target.desc), *wantedParams.toTypedArray())
        if (patch.replacementDescriptor != wanted) {
            throw PatchException(
                "${patch.replacementText} has descriptor ${patch.replacementDescriptor} but replacing ${patch.targetKey} needs $wanted" +
                    if (isStatic) "" else " (an instance method: the object is the first parameter)"
            )
        }
        replaceBody(target, wantedParams, patch)
    }
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    return writer.toByteArray()
}

private fun refuseUnsafe(method: MethodNode, patch: PatchSpec) {
    val key = patch.targetKey
    if (annotationOf(method, HOTFIX_TARGET_DESC) == null) throw PatchException("$key is not marked @HotfixTarget in the core, so it cannot be patched")
    if (method.name.startsWith("<")) throw PatchException("$key is a constructor or initializer; those cannot be patched")
    if ('-' in method.name) throw PatchException("$key has a mangled name (a value class in its signature); give it a stable @JvmName")
    if (method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) throw PatchException("$key has no body to replace (abstract or native)")
    if (method.access and (Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC) != 0) throw PatchException("$key is a compiler-generated method")
    val last = Type.getArgumentTypes(method.desc).lastOrNull()
    if (last != null && last.descriptor == "Lkotlin/coroutines/Continuation;") throw PatchException("$key looks like a suspend function; those cannot be patched")
}

private fun replaceBody(method: MethodNode, params: List<Type>, patch: PatchSpec) {
    method.instructions.clear()
    method.tryCatchBlocks = ArrayList()
    method.localVariables = null
    method.visibleLocalVariableAnnotations = null
    method.invisibleLocalVariableAnnotations = null
    var slot = 0
    for (type in params) {
        method.instructions.add(VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot))
        slot += type.size
    }
    method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, patch.replacementOwner, patch.replacementName, patch.replacementDescriptor, patch.replacementOwnerIsInterface))
    method.instructions.add(InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)))
    method.maxLocals = slot
}

private fun readClass(bytes: ByteArray): ClassNode {
    val node = ClassNode()
    try {
        ClassReader(bytes).accept(node, 0)
    } catch (e: RuntimeException) {
        throw PatchException("a class file cannot be read (${e.javaClass.simpleName}: ${e.message})")
    }
    return node
}

private fun annotationOf(method: MethodNode, desc: String): AnnotationNode? =
    (method.invisibleAnnotations.orEmpty() + method.visibleAnnotations.orEmpty()).firstOrNull { it.desc == desc }
