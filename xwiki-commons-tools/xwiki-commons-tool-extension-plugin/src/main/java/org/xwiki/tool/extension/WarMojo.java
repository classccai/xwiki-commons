/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.tool.extension;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.xwiki.component.embed.EmbeddableComponentManager;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.extension.Extension;
import org.xwiki.extension.internal.ExtensionUtils;
import org.xwiki.extension.internal.converter.ExtensionIdConverter;
import org.xwiki.extension.repository.internal.ExtensionSerializer;
import org.xwiki.extension.repository.internal.local.DefaultLocalExtension;
import org.xwiki.properties.converter.Converter;

/**
 * Create a runnable XWiki instance using Jetty as the Servlet Container and HSQLDB as the Database.
 *
 * @version $Id$
 * @since 8.4RC1
 */
@Mojo(name = "war", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, requiresDependencyResolution = ResolutionScope.RUNTIME, requiresProject = true, threadSafe = true)
public class WarMojo extends AbstractMojo
{
    /**
     * The directory where the war is generated.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}", required = true)
    private File webappDirectory;

    /**
     * The directory where the war is generated.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}/", required = true)
    private File resourceDirectory;

    /**
     * The maven project.
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    protected MavenProject project;

    /**
     * The current Maven session being executed.
     */
    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    @Parameter
    private List<ExtensionOverride> extensionOverrides;

    /**
     * Project builder -- builds a model from a pom.xml.
     */
    @Component
    protected ProjectBuilder projectBuilder;

    private ExtensionSerializer extensionSerializer;

    private Converter<Extension> extensionConverter;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        // Initialize ComponentManager
        EmbeddableComponentManager componentManager = new EmbeddableComponentManager();
        componentManager.initialize(this.getClass().getClassLoader());

        // Initialize components
        try {
            this.extensionSerializer = componentManager.getInstance(ExtensionSerializer.class);
            this.extensionConverter =
                componentManager.getInstance(new DefaultParameterizedType(null, Converter.class, Extension.class));
        } catch (ComponentLookupException e) {
            throw new MojoExecutionException("Failed to load components", e);
        }

        // Register the WAR
        registerWAR();

        // Register dependencies
        registerDependencies();
    }

    private void override(DefaultLocalExtension extension)
    {
        for (ExtensionOverride extensionOverride : this.extensionOverrides) {
            String id = extensionOverride.get(Extension.FIELD_ID);
            if (id != null) {
                // Override features
                String featuresString = extensionOverride.get(Extension.FIELD_FEATURES);
                System.out.println(featuresString);
                if (featuresString != null) {
                    Collection<String> features = ExtensionUtils.importPropertyStringList(featuresString, true);
                    System.out.println(features);
                    extension.setExtensionFeatures(
                        ExtensionIdConverter.toExtensionIdList(features, extension.getId().getVersion()));
                    System.out.println(extension.getExtensionFeatures());
                }
            }
        }
    }

    private void registerWAR() throws MojoExecutionException
    {
        // Make sure "/META-INF/" exists
        File directory = new File(this.resourceDirectory, "META-INF");
        directory.mkdirs();

        // Write descriptor
        try {
            register(new File(directory, "extension.xed"), this.project.getModel());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to write WAR descriptor", e);
        }
    }

    private void register(File path, Model model) throws IOException, ParserConfigurationException, TransformerException
    {
        // Get Extension instance
        Extension mavenExtension = this.extensionConverter.convert(Extension.class, model);
        DefaultLocalExtension extension = new DefaultLocalExtension(null, mavenExtension);

        if (!path.exists()) {
            // Apply overrides
            override(extension);

            // Save the Extension descriptor
            try (FileOutputStream stream = new FileOutputStream(path)) {
                this.extensionSerializer.saveExtensionDescriptor(extension, stream);
            }
        }
    }

    private void registerDependencies() throws MojoExecutionException
    {
        // Make sure "/WEB-INF/lib/" exist
        File libDirectory = new File(this.webappDirectory, "WEB-INF/lib/");
        libDirectory.mkdirs();

        // Register dependencies
        for (Artifact artifact : this.project.getArtifacts()) {
            if (!artifact.isOptional()) {
                if ("jar".equals(artifact.getType())) {
                    registerDependency(artifact, libDirectory);
                }
            }
        }
    }

    private void registerDependency(Artifact artifact, File libDirectory) throws MojoExecutionException
    {
        // Get MavenProject instance
        MavenProject dependencyProject = getMavenProject(artifact);

        // Get path
        File path = new File(libDirectory, artifact.getArtifactId() + '-' + artifact.getVersion() + ".xed");

        try {
            register(path, dependencyProject.getModel());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to write descriptor for artifact [" + artifact + "]", e);
        }
    }

    private MavenProject getMavenProject(Artifact artifact) throws MojoExecutionException
    {
        try {
            ProjectBuildingRequest request = new DefaultProjectBuildingRequest(this.session.getProjectBuildingRequest())
                // We don't want to execute any plugin here
                .setProcessPlugins(false)
                // It's not this plugin job to validate this pom.xml
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                // Use the repositories configured for the built project instead of the default Maven ones
                .setRemoteRepositories(this.session.getCurrentProject().getRemoteArtifactRepositories());
            // Note: build() will automatically get the POM artifact corresponding to the passed artifact.
            ProjectBuildingResult result = this.projectBuilder.build(artifact, request);
            return result.getProject();
        } catch (ProjectBuildingException e) {
            throw new MojoExecutionException(String.format("Failed to build project for [%s]", artifact), e);
        }
    }
}
