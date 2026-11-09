package io.ezeebit.architecture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.util.ClassUtils;

// Reads what a @Bean method instantiates, lambdas included, so a declared interface or framework type hides nothing.
final class BeanBodies {

	private BeanBodies() {
	}

	static List<Class<?>> constructedIn(final Method bean) {
		Class<?> config = bean.getDeclaringClass();
		Set<String> names = new TreeSet<>();
		read(config).accept(new BodyVisitor(bean.getName(), names), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return names.stream().<Class<?>>map(name -> ClassUtils.resolveClassName(name, config.getClassLoader())).toList();
	}

	private static ClassReader read(final Class<?> config) {
		String resource = "%s.class".formatted(config.getName().replace('.', '/'));
		try (InputStream bytes = config.getClassLoader().getResourceAsStream(resource)) {
			return new ClassReader(bytes);
		}
		catch (IOException unreadable) {
			throw new UncheckedIOException("cannot read %s".formatted(resource), unreadable);
		}
	}

	private static final class BodyVisitor extends ClassVisitor {

		private final String beanName;
		private final Set<String> names;

		private BodyVisitor(final String beanName, final Set<String> names) {
			super(Opcodes.ASM9);
			this.beanName = beanName;
			this.names = names;
		}

		@Override
		public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature,
				final String[] exceptions) {
			if (!name.equals(beanName) && !name.startsWith("lambda$%s$".formatted(beanName))) {
				return null;
			}
			return new MethodVisitor(Opcodes.ASM9) {
				@Override
				public void visitTypeInsn(final int opcode, final String type) {
					if (opcode == Opcodes.NEW) {
						names.add(type.replace('/', '.'));
					}
				}
			};
		}

	}

}
