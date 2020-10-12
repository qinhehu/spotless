/*
 * Copyright 2016-2020 DiffPlug
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
package com.diffplug.spotless.kotlin;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.diffplug.spotless.FormatterFunc;
import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.JarState;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.ThrowingEx;

/**
 * Wraps up [ktlint](https://github.com/pinterest/ktlint) as a FormatterStep.
 */
public class KtLintStep {
	// prevent direct instantiation
	private KtLintStep() {
	}

	private static int badSemver(String input) {
		Matcher matcher = BAD_SEMVER.matcher(input);
		if (!matcher.find() || matcher.start() != 0) {
			throw new IllegalArgumentException("Version must start with " + BAD_SEMVER.pattern());
		}
		String major = matcher.group(1);
		String minor = matcher.group(2);
		return badSemver(Integer.parseInt(major), Integer.parseInt(minor));
	}

	/**
	 * Ambiguous after 2147.483647.blah-blah
	 */
	private static int badSemver(int major, int minor) {
		return major * 1_000_000 + minor;
	}

	private static final Pattern BAD_SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.");

	private static final String DEFAULT_VERSION = "0.35.0";
	static final String NAME = "ktlint";
	static final String PACKAGE_PRE_0_32 = "com.github.shyiko";
	static final String PACKAGE = "com.pinterest";
	static final String MAVEN_COORDINATE_PRE_0_32 = PACKAGE_PRE_0_32 + ":ktlint:";
	static final String MAVEN_COORDINATE = PACKAGE + ":ktlint:";

	public static FormatterStep create(Provisioner provisioner) {
		return create(defaultVersion(), provisioner);
	}

	public static FormatterStep create(String version, Provisioner provisioner) {
		return create(version, provisioner, Collections.emptyMap(), null, null);
	}

	public static FormatterStep create(String version, Provisioner provisioner, Map<String, String> userData, String libs, String maven) {
		return create(version, provisioner, false, userData, libs, maven);
	}

	public static FormatterStep createForScript(String version, Provisioner provisioner) {
		return create(version, provisioner, true, Collections.emptyMap(), null, null);
	}

	public static FormatterStep createForScript(String version, Provisioner provisioner, Map<String, String> userData) {
		return create(version, provisioner, true, userData, null, null);
	}

	private static FormatterStep create(String version, Provisioner provisioner, boolean isScript, Map<String, String> userData, String libs, String maven) {
		Objects.requireNonNull(version, "version");
		Objects.requireNonNull(provisioner, "provisioner");
		return FormatterStep.createLazy(NAME,
			() -> new State(version, provisioner, isScript, userData, libs, maven),
			State::createFormat);
	}

	public static String defaultVersion() {
		return DEFAULT_VERSION;
	}

	static final class State implements Serializable {
		private static final long serialVersionUID = 1L;

		/**
		 * Are the files being linted Kotlin script files.
		 */
		private final boolean isScript;
		private final String pkg;
		/**
		 * The jar that contains the eclipse formatter.
		 */
		final JarState jarState;
		private final TreeMap<String, String> userData;
		private final boolean useParams;

		//		JarState cstJarState;
		private final String libs;

		State(String version, Provisioner provisioner, boolean isScript, Map<String, String> userData, String libs, String maven) throws IOException {
			this.userData = new TreeMap<>(userData);
			String coordinate;
			if (badSemver(version) < badSemver(0, 32)) {
				coordinate = MAVEN_COORDINATE_PRE_0_32;
				this.pkg = PACKAGE_PRE_0_32;
			} else {
				coordinate = MAVEN_COORDINATE;
				this.pkg = PACKAGE;
			}
			this.useParams = badSemver(version) >= badSemver(0, 34);
			if (maven == null) {
				this.jarState = JarState.from(coordinate + version, provisioner);
			} else {
//				this.jarState = JarState.from("com.zozo.buriedpoint:buried-point-plugin:1.3.1", provisioner);
				this.jarState = JarState.from(maven, provisioner);
			}

			this.isScript = isScript;

//			cstJarState = JarState.from("com.zozo.buriedpoint:buried-point-plugin:1.3.1", provisioner);
//			this.provisioner = provisioner;
			this.libs = libs;
		}

		FormatterFunc createFormat() throws Exception {
			ClassLoader classLoader = jarState.getClassLoader();

			// String KtLint::format(String input, Iterable<RuleSet> rules, Function2 errorCallback)

			// first, we get the standard rules
			Class<?> standardRuleSetProviderClass = classLoader.loadClass(pkg + ".ktlint.ruleset.standard.StandardRuleSetProvider");
			Object standardRuleSet = standardRuleSetProviderClass.getMethod("get").invoke(standardRuleSetProviderClass.newInstance());
//			Iterable<?> ruleSets = Collections.singletonList(standardRuleSet);


			ArrayList<Object> ruleList = new ArrayList<>();
			ruleList.add(standardRuleSet);

			if (libs != null) {
				for (String lib : libs.split(",")) {
					System.out.println(lib + "====");
//					ClassLoader cl = cstJarState.getClassLoader();
					Class<?> cstRuleSetProviderClass = classLoader.loadClass(lib);
					if(cstRuleSetProviderClass!=null){
						Object cstRuleSet = standardRuleSet.getClass().cast(cstRuleSetProviderClass.getMethod("get").invoke(cstRuleSetProviderClass.newInstance()));
						ruleList.add(cstRuleSet);
					}
				}
			}

			// next, we create an error callback which throws an assertion error when the format is bad
			Class<?> function2Interface = classLoader.loadClass("kotlin.jvm.functions.Function2");
			Class<?> lintErrorClass = classLoader.loadClass(pkg + ".ktlint.core.LintError");
			Method detailGetter = lintErrorClass.getMethod("getDetail");
			Method lineGetter = lintErrorClass.getMethod("getLine");
			Method colGetter = lintErrorClass.getMethod("getCol");
			Object formatterCallback = Proxy.newProxyInstance(classLoader, new Class[]{function2Interface},
				(proxy, method, args) -> {
					Object lintError = args[0]; //ktlint.core.LintError
					boolean corrected = (Boolean) args[1];
					if (!corrected) {
						String detail = (String) detailGetter.invoke(lintError);
						int line = (Integer) lineGetter.invoke(lintError);
						int col = (Integer) colGetter.invoke(lintError);
						throw new AssertionError("Error on line: " + line + ", column: " + col + "\n" + detail);
					}
					return null;
				});

			// grab the KtLint singleton
			Class<?> ktlintClass = classLoader.loadClass(pkg + ".ktlint.core.KtLint");
			Object ktlint = ktlintClass.getDeclaredField("INSTANCE").get(null);
			FormatterFunc formatterFunc;
			if (useParams) {
				//
				// In KtLint 0.34+ there is a new "format(params: Params)" function. We create an
				// instance of the Params class with our configuration and invoke it here.
				//

				// grab the Params class
				Class<?> paramsClass = classLoader.loadClass(pkg + ".ktlint.core.KtLint$Params");
				// and its constructor
				Constructor<?> constructor = paramsClass.getConstructor(
					/* fileName, nullable */ String.class,
					/* text */ String.class,
					/* ruleSets */ Iterable.class,
					/* userData */ Map.class,
					/* callback */ function2Interface,
					/* script */ boolean.class,
					/* editorConfigPath, nullable */ String.class,
					/* debug */ boolean.class);
				Method formatterMethod = ktlintClass.getMethod("format", paramsClass);
				formatterFunc = input -> {
					try {
						Object params = constructor.newInstance(
							/* fileName, nullable */ null,
							/* text */ input,
							/* ruleSets */ ruleList,
							/* userData */ userData,
							/* callback */ formatterCallback,
							/* script */ isScript,
							/* editorConfigPath, nullable */ null,
							/* debug */ false);
						return (String) formatterMethod.invoke(ktlint, params);
					} catch (InvocationTargetException e) {
						throw ThrowingEx.unwrapCause(e);
					}
				};
			} else {
				// and its format method
				String formatterMethodName = isScript ? "formatScript" : "format";
				Method formatterMethod = ktlintClass.getMethod(formatterMethodName, String.class, Iterable.class, Map.class, function2Interface);
				formatterFunc = input -> {
					try {
						return (String) formatterMethod.invoke(ktlint, input, ruleList, userData, formatterCallback);
					} catch (InvocationTargetException e) {
						throw ThrowingEx.unwrapCause(e);
					}
				};
			}

			return formatterFunc;
		}
	}
}
