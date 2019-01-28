/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.entrypoint.patches;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.entrypoint.EntrypointPatch;
import net.fabricmc.loader.entrypoint.EntrypointTransformer;
import net.fabricmc.loader.launch.common.FabricLauncher;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;

public class EntrypointPatchHook extends EntrypointPatch {
	public EntrypointPatchHook(EntrypointTransformer transformer) {
		super(transformer);
	}

	private void finishEntrypoint(EnvType type, ListIterator<AbstractInsnNode> it) {
		it.add(new VarInsnNode(Opcodes.ALOAD, 0));
		it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/entrypoint/hooks/Entrypoint" + (type == EnvType.CLIENT ? "Client" : "Server"), "start", "(Ljava/io/File;Ljava/lang/Object;)V", false));
	}

	@Override
	public void process(FabricLauncher launcher, Consumer<ClassNode> classEmitter) {
		EnvType type = launcher.getEnvironmentType();
		String entrypoint = launcher.getEntrypoint();

		try {
			String gameEntrypoint = null;
			boolean serverHasFile = true;
			boolean isApplet = entrypoint.contains("Applet");
			ClassNode mainClass = loadClass(launcher, entrypoint);

			if (mainClass == null) {
				throw new RuntimeException("Could not load main class " + entrypoint + "!");
			}

			// Main -> Game entrypoint search
			//
			// -- CLIENT --
			// pre-1.6 (seems to hold to 0.0.11!): find the only non-static non-java-packaged Object field
			// 1.6.1+: [client].start() [INVOKEVIRTUAL]
			// 19w04a: [client].<init> [INVOKESPECIAL] -> Thread.start()
			// -- SERVER --
			// (1.5-1.7?)-: Just find it instantiating itself.
			// (1.6-1.8?)+: an <init> starting with java.io.File can be assumed to be definite

			if (type == EnvType.CLIENT) {
				// pre-1.6 route
				List<FieldNode> newGameFields = findFields(mainClass,
					(f) -> !isStatic(f.access) && f.desc.startsWith("L") && !f.desc.startsWith("Ljava/")
				);

				if (newGameFields.size() == 1) {
					gameEntrypoint = Type.getType(newGameFields.get(0).desc).getClassName();
				}
			}

			if (gameEntrypoint == null) {
				// main method searches
				MethodNode mainMethod = findMethod(mainClass, (method) -> method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V") && isPublicStatic(method.access));
				if (mainMethod == null) {
					throw new RuntimeException("Could not find main method in " + entrypoint + "!");
				}

				if (type == EnvType.SERVER) {
					// pre-1.6 method search route
					MethodInsnNode newGameInsn = (MethodInsnNode) findInsn(mainMethod,
						(insn) -> insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>") && ((MethodInsnNode) insn).owner.equals(mainClass.name),
						false
					);

					if (newGameInsn != null) {
						gameEntrypoint = newGameInsn.owner.replace('/', '.');
						serverHasFile = newGameInsn.desc.startsWith("(Ljava/io/File;");
					}
				}

				if (gameEntrypoint == null) {
					// modern method search routes
					MethodInsnNode newGameInsn = (MethodInsnNode) findInsn(mainMethod,
						type == EnvType.CLIENT
						? (insn) -> (insn.getOpcode() == Opcodes.INVOKESPECIAL || insn.getOpcode() == Opcodes.INVOKEVIRTUAL) && !((MethodInsnNode) insn).owner.startsWith("java/")
						: (insn) -> insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>") && ((MethodInsnNode) insn).desc.startsWith("(Ljava/io/File;"),
						true
					);

					if (newGameInsn != null) {
						gameEntrypoint = newGameInsn.owner.replace('/', '.');
					}
				}
			}

			if (gameEntrypoint == null) {
				throw new RuntimeException("Could not find game constructor in " + entrypoint + "!");
			}

			debug("Found game constructor: " + entrypoint + " -> " + gameEntrypoint);
			ClassNode gameClass = gameEntrypoint.equals(entrypoint) ? mainClass : loadClass(launcher, gameEntrypoint);
			if (gameClass == null) {
				throw new RuntimeException("Could not load game class " + gameEntrypoint + "!");
			}

			boolean patched = false;
			for (MethodNode gameMethod : gameClass.methods) {
				if (gameMethod.name.equals("<init>")) {
					debug("Patching game constructor " + gameMethod.desc);

					ListIterator<AbstractInsnNode> it = gameMethod.instructions.iterator();
					if (type == EnvType.SERVER) {
						// Server-side: first argument (or null!) is runDirectory, run at end of init
						moveBefore(it, Opcodes.RETURN);
						// runDirectory
						if (serverHasFile) {
							it.add(new VarInsnNode(Opcodes.ALOAD, 1));
						} else {
							it.add(new InsnNode(Opcodes.ACONST_NULL));
						}
						finishEntrypoint(type, it);
						patched = true;
					} else if (type == EnvType.CLIENT && isApplet) {
						// Applet-side: field is private static File, run at end
						// At the beginning, set file field (hook)
						FieldNode runDirectory = findField(gameClass, (f) -> isStatic(f.access) && f.desc.equals("Ljava/io/File;"));
						if (runDirectory == null) {
							// TODO: Handle pre-indev versions.
							//
							// Classic has no agreed-upon run directory.
							// - level.dat is always stored in CWD. We can assume CWD is set, launchers generally adhere to that.
							// - options.txt in newer Classic versions is stored in user.home/.minecraft/. This is not currently handled,
							// but as these versions are relatively low on options this is not a huge concern.
							warn("Could not find applet run directory! (If you're running pre-late-indev versions, this is fine.)");

							moveBefore(it, Opcodes.RETURN);
/*							it.add(new TypeInsnNode(Opcodes.NEW, "java/io/File"));
							it.add(new InsnNode(Opcodes.DUP));
							it.add(new LdcInsnNode("."));
							it.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)); */
							it.add(new InsnNode(Opcodes.ACONST_NULL));
							it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/entrypoint/applet/AppletMain", "hookGameDir", "(Ljava/io/File;)Ljava/io/File;", false));
							finishEntrypoint(type, it);
						} else {
							// Indev and above.
							moveAfter(it, Opcodes.INVOKESPECIAL); /* Object.init */
							it.add(new FieldInsnNode(Opcodes.GETSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));
							it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/entrypoint/applet/AppletMain", "hookGameDir", "(Ljava/io/File;)Ljava/io/File;", false));
							it.add(new FieldInsnNode(Opcodes.PUTSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));

							moveBefore(it, Opcodes.RETURN);
							it.add(new FieldInsnNode(Opcodes.GETSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));
							finishEntrypoint(type, it);
						}
						patched = true;
					} else {
						// Client-side: identify runDirectory field + location, run immediately after
						while (it.hasNext()) {
							AbstractInsnNode insn = it.next();
							if (insn.getOpcode() == Opcodes.PUTFIELD
								&& ((FieldInsnNode) insn).desc.equals("Ljava/io/File;")) {
								debug("Run directory field is thought to be " + ((FieldInsnNode) insn).owner + "/" + ((FieldInsnNode) insn).name);

								it.add(new VarInsnNode(Opcodes.ALOAD, 0));
								it.add(new FieldInsnNode(Opcodes.GETFIELD, ((FieldInsnNode) insn).owner, ((FieldInsnNode) insn).name, ((FieldInsnNode) insn).desc));
								finishEntrypoint(type, it);

								patched = true;
								break;
							}
						}
					}
				}
			}

			if (!patched) {
				throw new RuntimeException("Game constructor patch not applied!");
			}

			if (gameClass != mainClass) {
				classEmitter.accept(gameClass);
			} else {
				classEmitter.accept(mainClass);
			}

			if (isApplet) {
				EntrypointTransformer.appletMainClass = entrypoint;
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}