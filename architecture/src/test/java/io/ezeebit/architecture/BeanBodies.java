package io.ezeebit.architecture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

// Finds a config's @Bean methods and reads what each instantiates, so a declared interface or framework type hides nothing.
final class BeanBodies {

	private BeanBodies() {
	}

	static List<Method> beanMethods(final Class<?> config) {
		Set<Class<?>> owners = new LinkedHashSet<>();
		addHierarchy(config, owners);
		return owners.stream()
				.filter(owner -> owner.getName().startsWith(InjectedSeams.PROJECT_ROOT))
				.flatMap(owner -> Arrays.stream(owner.getDeclaredMethods()))
				.filter(method -> AnnotatedElementUtils.hasAnnotation(method, Bean.class))
				.toList();
	}

	private static void addHierarchy(final Class<?> type, final Set<Class<?>> owners) {
		if (type != null && owners.add(type)) {
			addHierarchy(type.getSuperclass(), owners);
			Arrays.stream(type.getInterfaces()).forEach(parent -> addHierarchy(parent, owners));
		}
	}

	static List<Class<?>> constructedIn(final Method bean) {
		Class<?> config = bean.getDeclaringClass();
		Set<String> names = new TreeSet<>();
		read(config).accept(new BodyVisitor(bean, names), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return names.stream()
				.filter(name -> name.startsWith(InjectedSeams.PROJECT_ROOT))
				.<Class<?>>map(name -> ClassUtils.resolveClassName(name, config.getClassLoader()))
				.toList();
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
		private final String beanDescriptor;
		private final Set<String> names;

		private BodyVisitor(final Method bean, final Set<String> names) {
			super(Opcodes.ASM9);
			this.beanName = bean.getName();
			this.beanDescriptor = Type.getMethodDescriptor(bean);
			this.names = names;
		}

		@Override
		public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature,
				final String[] exceptions) {
			boolean body = name.equals(beanName) && descriptor.equals(beanDescriptor);
			if (!body && !name.startsWith("lambda$%s$".formatted(beanName))) {
				return null;
			}
			return new MethodVisitor(Opcodes.ASM9) {
				@Override
				public void visitTypeInsn(final int opcode, final String type) {
					if (opcode == Opcodes.NEW) {
						names.add(type.replace('/', '.'));
					}
				}

				@Override
				public void visitInvokeDynamicInsn(final String dynamicName, final String dynamicDescriptor, final Handle bootstrap,
						final Object... arguments) {
					Arrays.stream(arguments).filter(Handle.class::isInstance).map(Handle.class::cast)
							.filter(handle -> handle.getTag() == Opcodes.H_NEWINVOKESPECIAL)
							.forEach(handle -> names.add(handle.getOwner().replace('/', '.')));
				}
			};
		}

	}

}
