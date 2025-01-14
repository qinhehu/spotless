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
package com.diffplug.gradle.spotless;

import static com.diffplug.spotless.kotlin.KotlinConstants.LICENSE_HEADER_DELIMITER;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;

import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.kotlin.KtLintStep;
import com.diffplug.spotless.kotlin.KtfmtStep;
import com.diffplug.spotless.kotlin.KtfmtStep.Style;

public class KotlinExtension extends FormatExtension implements HasBuiltinDelimiterForLicense {
	static final String NAME = "kotlin";

	@Inject
	public KotlinExtension(SpotlessExtension spotless) {
		super(spotless);
	}

	@Override
	public LicenseHeaderConfig licenseHeader(String licenseHeader) {
		return licenseHeader(licenseHeader, LICENSE_HEADER_DELIMITER);
	}

	@Override
	public LicenseHeaderConfig licenseHeaderFile(Object licenseHeaderFile) {
		return licenseHeaderFile(licenseHeaderFile, LICENSE_HEADER_DELIMITER);
	}

	/**
	 * Adds the specified version of [ktlint](https://github.com/pinterest/ktlint).
	 */
	public KotlinFormatExtension ktlint(String version) {
		Objects.requireNonNull(version);
		return new KotlinFormatExtension(version, Collections.emptyMap(), null);
	}

	public KotlinFormatExtension ktlint() {
		return ktlint(KtLintStep.defaultVersion());
	}

	public class KotlinFormatExtension {

		private final String version;
		private Map<String, String> userData;
		private String libs;
		private String maven;

		KotlinFormatExtension(String version, Map<String, String> config, String libs) {
			this.version = version;
			this.userData = config;
			this.libs = libs;
			addStep(createStep());
		}

		public KotlinFormatExtension userData(Map<String, String> userData) {
			// Copy the map to a sorted map because up-to-date checking is based on binary-equals of the serialized
			// representation.
			this.userData = userData;
			return this;
		}

		public KotlinFormatExtension libs(String libs) {
			this.libs = libs;
			return this;
		}

		public KotlinFormatExtension mainMaven(String maven) {
			this.maven = maven;
			return this;
		}

		public void replace() {
			replaceStep(createStep());
		}

		private FormatterStep createStep() {
			return KtLintStep.create(version, provisioner(), userData, libs, maven);
		}
	}

	/**
	 * Uses the [ktfmt](https://github.com/facebookincubator/ktfmt) jar to format source code.
	 */
	public KtfmtConfig ktfmt() {
		return ktfmt(KtfmtStep.defaultVersion());
	}

	/**
	 * Uses the given version of [ktfmt](https://github.com/facebookincubator/ktfmt) and applies the dropbox style
	 * option to format source code.
	 */
	public KtfmtConfig ktfmt(String version) {
		Objects.requireNonNull(version);
		return new KtfmtConfig(version);
	}

	public class KtfmtConfig {
		final String version;
		Style style;

		KtfmtConfig(String version) {
			this.version = Objects.requireNonNull(version);
			this.style = Style.DEFAULT;
			addStep(createStep());
		}

		public void dropboxStyle() {
			style = Style.DROPBOX;
			replaceStep(createStep());
		}

		private FormatterStep createStep() {
			return KtfmtStep.create(version, provisioner(), style);
		}
	}

	/**
	 * If the user hasn't specified the files yet, we'll assume he/she means all of the kotlin files.
	 */
	@Override
	protected void setupTask(SpotlessTask task) {
		if (target == null) {
			JavaPluginConvention javaPlugin = getProject().getConvention().findPlugin(JavaPluginConvention.class);
			if (javaPlugin == null) {
				throw new GradleException("You must either specify 'target' manually or apply a kotlin plugin.");
			}
			FileCollection union = getProject().files();
			for (SourceSet sourceSet : javaPlugin.getSourceSets()) {
				union = union.plus(sourceSet.getAllSource().filter(file -> {
					String name = file.getName();
					return name.endsWith(".kt") || name.endsWith(".kts");
				}));
			}
			target = union;
		}
		super.setupTask(task);
	}
}
