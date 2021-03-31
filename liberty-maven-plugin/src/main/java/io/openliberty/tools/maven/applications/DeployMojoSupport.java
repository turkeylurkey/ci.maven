/**
 * (C) Copyright IBM Corporation 2016, 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.tools.maven.applications;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.Validate;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.tools.ant.taskdefs.Copy;
import org.codehaus.mojo.pluginsupport.util.ArtifactItem;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.openliberty.tools.ant.ServerTask;
import io.openliberty.tools.ant.SpringBootUtilTask;
import io.openliberty.tools.maven.server.PluginConfigSupport;
import io.openliberty.tools.maven.utils.CommonLogger;
import io.openliberty.tools.maven.utils.MavenProjectUtil;
import io.openliberty.tools.common.plugins.config.ApplicationXmlDocument;
import io.openliberty.tools.common.plugins.config.LooseApplication;
import io.openliberty.tools.common.plugins.config.LooseConfigData;
import io.openliberty.tools.common.plugins.config.ServerConfigDocument;
import io.openliberty.tools.common.plugins.util.DevUtil;

/**
 * Support for installing and deploying applications to a Liberty server.
 */
public class DeployMojoSupport extends PluginConfigSupport {

    private final String PROJECT_ROOT_TARGET_LIBS = "target/libs";

    /**
     * Timeout to verify deploy successfully, in seconds.
     */
    @Parameter(property = "timeout", defaultValue = "40")
    protected long timeout = 40;
    
    /**
     * When deploying loose applications, the optional directory to which application dependencies are copied.
     */
    @Parameter(property = "copyLibsDirectory")
    protected File copyLibsDirectory;

    protected ApplicationXmlDocument applicationXml = new ApplicationXmlDocument();

    protected void installApp(Artifact artifact) throws Exception {
    
        if (artifact.getFile() == null || artifact.getFile().isDirectory()) {
            String warName = getAppFileName(project);
            File f = new File(project.getBuild().getDirectory() + "/" + warName);
            artifact.setFile(f);
        }

        if (!artifact.getFile().exists()) {
            throw new MojoExecutionException(messages.getString("error.install.app.missing"));
        }

        File destDir = new File(serverDirectory, getAppsDirectory());
        log.info(MessageFormat.format(messages.getString("info.install.app"), artifact.getFile().getCanonicalPath()));

        Copy copyFile = (Copy) ant.createTask("copy");
        copyFile.setFile(artifact.getFile());
        String fileName = artifact.getFile().getName();
        if (stripVersion) {
            fileName = stripVersionFromName(fileName, artifact.getBaseVersion());
            copyFile.setTofile(new File(destDir, fileName));
        } else {
            copyFile.setTodir(destDir);
        }

        // validate application configuration if appsDirectory="dropins" or inject
        // webApplication
        // to target server.xml if not found for appsDirectory="apps"
        validateAppConfig(fileName, artifact.getArtifactId());

        deleteApplication(new File(serverDirectory, "apps"), artifact.getFile());
        deleteApplication(new File(serverDirectory, "dropins"), artifact.getFile());
        // application can be expanded if server.xml configure with <applicationManager
        // autoExpand="true"/>
        deleteApplication(new File(serverDirectory, "apps/expanded"), artifact.getFile());
        copyFile.execute();

        verifyAppStarted(fileName);
    }

    // install war project artifact using loose application configuration file
    protected void installLooseConfigWar(MavenProject proj, LooseConfigData config, boolean container) throws Exception {
        // return error if webapp contains java source but it is not compiled yet.
        File dir = new File(proj.getBuild().getOutputDirectory());
        if (!dir.exists() && containsJavaSource(proj)) {
            throw new MojoExecutionException(
                    MessageFormat.format(messages.getString("error.project.not.compile"), proj.getId()));
        }

        if (container) {
                try {
                    // Set up the config to replace the absolute path names with ${variable}/target type references
                    config.setProjectRoot(proj.getBasedir().getCanonicalPath());
                    config.setSourceOnDiskName("${"+DevUtil.DEVMODE_PROJECT_ROOT+"}");
                    if (copyLibsDirectory == null) { // in container mode, copy dependencies from .m2 dir to the target dir to mount in container
                        copyLibsDirectory = new File(proj.getBasedir(), PROJECT_ROOT_TARGET_LIBS);
                    } else {
                        // test the user defined copyLibsDirectory parameter for use in a container
                        String projectPath = proj.getBasedir().getCanonicalPath();
                        String copyLibsPath = copyLibsDirectory.getCanonicalPath();
                        if (!copyLibsPath.startsWith(projectPath)) {
                            // Flag an error but allow processing to continue in case dependencies, if any, are not actually referenced by the app.
                            log.error("The directory indicated by the copyLibsDirectory parameter must be within the Maven project directory when the container option is specified.");
                        }
                    }
                } catch (IOException e) {
                    // an IOException here should fail the build
                    throw new MojoExecutionException("Could not resolve the canonical path of the Maven project or the directory specified in the copyLibsDirectory parameter. Exception message:" + e.getMessage(), e);
                }
        }

        LooseWarApplication looseWar = new LooseWarApplication(proj, config);
        looseWar.addSourceDir(proj);
        looseWar.addOutputDir(looseWar.getDocumentRoot(), new File(proj.getBuild().getOutputDirectory()),
                "/WEB-INF/classes");

        // retrieve the directories defined as resources in the maven war plugin
        Map<String,String> webResources = MavenProjectUtil.getWebResourcesConfiguration(proj);
        if (webResources != null) {
            for (String directory : webResources.keySet()) {
                String targetPath = webResources.get(directory)==null ? "/" : "/"+webResources.get(directory);
                looseWar.addOutputDir(looseWar.getDocumentRoot(), new File(proj.getBasedir().getAbsolutePath(), directory), targetPath);
            }
        }

        // retrieves dependent library jar files
        addEmbeddedLib(looseWar.getDocumentRoot(), proj, looseWar, "/WEB-INF/lib/");

        // add Manifest file
        File manifestFile = MavenProjectUtil.getManifestFile(proj, "maven-war-plugin");
        looseWar.addManifestFile(manifestFile);
    }

    // install ear project artifact using loose application configuration file
    protected void installLooseConfigEar(MavenProject proj, LooseConfigData config) throws Exception {
        LooseEarApplication looseEar = new LooseEarApplication(proj, config);
        looseEar.addSourceDir();
        looseEar.addApplicationXmlFile();

        Set<Artifact> artifacts = proj.getArtifacts();
        log.debug("Number of compile dependencies for " + proj.getArtifactId() + " : " + artifacts.size());
        log.warn("Number of compile dependencies for " + proj.getArtifactId() + " : " + artifacts.size());
        Map<String, ProjectBuildingResult> moduleDescriptors = null;
        if (parentHasModules(proj)) {
            moduleDescriptors = getModules(proj.getParent());
        }

        for (Artifact artifact : artifacts) {
        	log.warn("artifact="+artifact.getArtifactId()+" scope="+artifact.getScope());
            if (Artifact.SCOPE_COMPILE.equals(artifact.getScope()) || Artifact.SCOPE_RUNTIME.equals(artifact.getScope())) {
        	    log.warn("show artifact full name:"+getFullName(artifact.getArtifactId(), artifact.getGroupId(), artifact.getVersion()));
                boolean b = isPeerModule(moduleDescriptors, artifact);
                log.warn("Peer? artifact "+artifact.getArtifactId()+" is peer of project "+b);
                if (isReactorMavenProject(artifact)) {
                    MavenProject dependencyProject = getReactorMavenProject(artifact);
                    log.warn("dependencyProject display name="+dependencyProject.getName()+" artifactid="+dependencyProject.getArtifactId()+":"+dependencyProject.getGroupId());
                    addLooseModule(looseEar, artifact, dependencyProject, null, null);
                } else if (isPeerModule(moduleDescriptors, artifact)) {
                    MavenProject dependencyProject = getPeerModule(moduleDescriptors, artifact);
                    log.warn("peer module Project display name="+dependencyProject.getName()+" artifactid="+dependencyProject.getArtifactId()+":"+dependencyProject.getGroupId());
                    addLooseModule(looseEar, artifact, dependencyProject, moduleDescriptors, null);
                } else {
                    log.warn("Set up loose ear ");
                    if (looseEar.isEarSkinnyWars() && "war".equals(artifact.getType())) {
                        throw new MojoExecutionException(
                                "Unable to create loose configuration for the EAR application with skinnyWars package from "
                                        + artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                                        + artifact.getVersion()
                                        + ". Please set the looseApplication configuration parameter to false and try again.");
                    }
                    looseEar.addModuleFromM2(resolveArtifact(artifact));
                }
            }
        }

        // add Manifest file
        File manifestFile = MavenProjectUtil.getManifestFile(proj, "maven-ear-plugin");
        looseEar.addManifestFile(manifestFile);
    }

	private Element addLooseModule(LooseEarApplication looseEar, Artifact artifact, MavenProject dependencyProject, Map<String, ProjectBuildingResult> moduleDescriptors, Element docRef)
			throws Exception, MojoExecutionException {
		Element newElement;
        log.warn("add Loose Module "+artifact.getGroupId()+":"+artifact.getArtifactId());
		switch (artifact.getType()) {
		case "jar":
			newElement = looseEar.addJarModule(dependencyProject);
		    log.warn(" add jar element children size:"+newElement.getChildNodes().getLength());
		    log.warn(" add jar element text:"+newElement.getTextContent());
		    log.warn(" add jar element targetInArchive attr:"+newElement.getAttribute("targetInArchive"));
		    break;
		case "ejb":
			newElement = looseEar.addEjbModule(dependencyProject);
		    break;
		case "war":
		    Element warArchive = looseEar.addWarModule(dependencyProject,
		            getWarSourceDirectory(dependencyProject));
		    log.warn(" add WAR element children size:"+warArchive.getChildNodes().getLength());
		    log.warn(" add WAR element first child node value:"+warArchive.getFirstChild().getNodeValue());
		    log.warn(" add WAR element targetInArchive attr:"+warArchive.getAttribute("targetInArchive"));
		    if (looseEar.isEarSkinnyWars()) {
		        // add embedded lib only if they are not a compile dependency in the ear
		        // project.
		        addSkinnyWarLib(warArchive, dependencyProject, looseEar);
		    } else if (moduleDescriptors != null) { // multimodule build
		    	Set<Artifact> artifacts = dependencyProject.getArtifacts();
		        log.warn("Number of compile dependencies for " + dependencyProject.getArtifactId() + " : " + artifacts.size());

		        for (Artifact a : artifacts) {
		            if ((Artifact.SCOPE_COMPILE.equals(a.getScope()) || Artifact.SCOPE_RUNTIME.equals(a.getScope()))
		                    && "jar".equals(a.getType()) && isPeerModule(moduleDescriptors, a)) {
		            	Element newJar = addLooseModule(looseEar, a, getPeerModule(moduleDescriptors, a), moduleDescriptors, warArchive);
		            	// newJar added to end of config. Move it into the war element.
		            	// TODO
		            	Node documentRoot = looseEar.getDocumentRoot();
		            	if (documentRoot instanceof Element) {
		            		Element topArchive = (Element) documentRoot;
		            		log.warn(" new Jar targetInArchive attr:"+topArchive.getAttribute("targetInArchive"));
			            	Node lastChild = topArchive.getLastChild(); // should be new Jar
			            	Element lastChildE = (Element) lastChild;
			            	log.warn(" last Child targetInArchive attr:"+lastChildE.getAttribute("targetInArchive"));
			            	log.warn(" last Child hash="+lastChild.hashCode());
			            	log.warn(" newJar hash="+newJar.hashCode());
			            	if (lastChild == newJar) {
			            		log.warn(" last Child is the same as newJar");
			            		topArchive.removeChild(lastChild);
			            		warArchive.appendChild(lastChild);
			            	}
		            	}
		            }
		        }
		    } else {
		        addEmbeddedLib(warArchive, dependencyProject, looseEar, "/WEB-INF/lib/");
		    }
		    newElement = warArchive;
		    break;
		case "rar":
		    Element rarArchive = looseEar.addRarModule(dependencyProject);
		    addEmbeddedLib(rarArchive, dependencyProject, looseEar, "/");
		    newElement = rarArchive;
		    break;
		default:
		    // use the artifact from local .m2 repo
			newElement = looseEar.addModuleFromM2(resolveArtifact(artifact));
		    break;
		}
        log.warn("exit add Loose Module "+artifact.getGroupId()+":"+artifact.getArtifactId());
        return newElement;
	}

    private boolean isPeerModule(Map<String, ProjectBuildingResult> moduleDescriptors, Artifact a) {
        log.warn("find artifact named "+getFullName(a.getArtifactId(), a.getGroupId(), a.getVersion()));
        if (moduleDescriptors == null) {
        	return false;
        }
        return (getPeerModule(moduleDescriptors, a) != null);
    }

    private MavenProject getPeerModule(Map<String, ProjectBuildingResult> moduleDescriptors, Artifact a) {
        log.warn("get project for artifact named "+getFullName(a.getArtifactId(), a.getGroupId(), a.getVersion()));
        String artifactName = getFullName(a.getArtifactId(), a.getGroupId(), a.getVersion());
        for (String key : moduleDescriptors.keySet()) {
        	//log.warn("getPeerModule, key="+key);
            if (key.equals(artifactName)) {
            	log.warn("found project "+moduleDescriptors.get(key).getProject().getArtifactId());
            	return moduleDescriptors.get(key).getProject();
            }
        }
        return null;
    }

    private boolean parentHasModules(MavenProject proj) {
        MavenProject parent = proj.getParent();
        if (parent != null) {
            List<String> modules = parent.getModules();
            if (modules != null && modules.size() > 0) {
            	log.warn("Parent has "+modules.size()+" modules");
                return true;
            }
        }
        log.warn("No parent or no modules");
        return false;
    }

    private Map<String, ProjectBuildingResult> getModules(MavenProject parent) {
        Map<String, ProjectBuildingResult> moduleFiles = new HashMap<String, ProjectBuildingResult>();
        List<String> modules = parent.getModules();
	    for (String m : modules) {
            log.warn("parent module:"+m);
            File moduleFile = new File(parent.getBasedir(), m + "/pom.xml");
            log.warn("module file="+moduleFile+" exists:"+moduleFile.exists());
            ProjectBuildingResult projectDescriptor;
            try {
            	log.warn("Build project descriptor from module pom");
    			projectDescriptor = mavenProjectBuilder.build(moduleFile, session.getProjectBuildingRequest().setResolveDependencies(true));
            } catch (ProjectBuildingException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
            	log.warn("*Error: ProjectBuildingException for " + m + " check file exists:"+moduleFile);
				continue;
	        }
            MavenProject p = projectDescriptor.getProject();
            log.warn("Save project named "+getFullName(p.getArtifactId(), p.getGroupId(), p.getVersion()));
            moduleFiles.put(getFullName(p.getArtifactId(), p.getGroupId(), p.getVersion()), projectDescriptor);
        }
        return moduleFiles;
    }

    private String getFullName(String a, String b, String c) {
        return new StringBuilder(a).append(':').append(b).append(':').append(c).toString();
    }

    private boolean shouldValidateAppStart() throws MojoExecutionException {
        try {
            return new File(serverDirectory.getCanonicalPath()  + "/workarea/.sRunning").exists();
        } catch (IOException ioe) {
            throw new MojoExecutionException("Could not get the server directory to determine the state of the server.");
        }
    }

    protected void verifyAppStarted(String appFile) throws MojoExecutionException {
        if (shouldValidateAppStart()) {
            String appName = appFile.substring(0, appFile.lastIndexOf('.'));
            if (getAppsDirectory().equals("apps")) {
                ServerConfigDocument scd = null;

                File serverXML = new File(serverDirectory, "server.xml");

                try {
                    scd = ServerConfigDocument.getInstance(CommonLogger.getInstance(), serverXML, configDirectory,
                            bootstrapPropertiesFile, combinedBootstrapProperties, serverEnvFile, false);

                    //appName will be set to a name derived from appFile if no name can be found.
                    appName = scd.findNameForLocation(appFile);
                } catch (Exception e) {
                    log.warn(e.getLocalizedMessage());
                    log.debug(e);
                } 
            }

            ServerTask serverTask = initializeJava();
            if (serverTask.waitForStringInLog(START_APP_MESSAGE_REGEXP + appName, timeout * 1000, new File(new File(outputDirectory, serverName), "logs/messages.log")) == null) {
                throw new MojoExecutionException(MessageFormat.format(messages.getString("error.deploy.fail"), appName));
            }
        }
    }

    private void addEmbeddedLib(Element parent, MavenProject proj, LooseApplication looseApp, String dir)
            throws Exception {
        Set<Artifact> artifacts = proj.getArtifacts();
        log.debug("Number of compile dependencies for " + proj.getArtifactId() + " : " + artifacts.size());

        for (Artifact artifact : artifacts) {
            if ((Artifact.SCOPE_COMPILE.equals(artifact.getScope()) || Artifact.SCOPE_RUNTIME.equals(artifact.getScope()))
                    && "jar".equals(artifact.getType())) {
                addLibrary(parent, looseApp, dir, artifact);
            }
        }
    }

    private void addSkinnyWarLib(Element parent, MavenProject proj, LooseEarApplication looseEar) throws Exception {
        Set<Artifact> artifacts = proj.getArtifacts();
        log.debug("Number of compile dependencies for " + proj.getArtifactId() + " : " + artifacts.size());

        for (Artifact artifact : artifacts) {
            // skip the embedded library if it is included in the lib directory of the ear
            // package
            if ((Artifact.SCOPE_COMPILE.equals(artifact.getScope()) || Artifact.SCOPE_RUNTIME.equals(artifact.getScope()))
                    && "jar".equals(artifact.getType()) && !looseEar.isEarDependency(artifact)) {
                addLibrary(parent, looseEar, "/WEB-INF/lib/", artifact);
            }
        }
    }

    private void addLibrary(Element parent, LooseApplication looseApp, String dir, Artifact artifact) throws Exception {
        {
            if (isReactorMavenProject(artifact)) {
                MavenProject dependProject = getReactorMavenProject(artifact);
                Element archive = looseApp.addArchive(parent, dir + dependProject.getBuild().getFinalName() + ".jar");
                looseApp.addOutputDir(archive, new File(dependProject.getBuild().getOutputDirectory()), "/");
                
                File manifestFile = MavenProjectUtil.getManifestFile(dependProject, "maven-jar-plugin");
                looseApp.addManifestFileWithParent(archive, manifestFile);
            } else {
                resolveArtifact(artifact);
                if(copyLibsDirectory != null) {
                    if(!copyLibsDirectory.exists()) {
                        copyLibsDirectory.mkdirs();
                    }
                    if(!copyLibsDirectory.isDirectory()) {
                        throw new MojoExecutionException("copyLibsDirectory must be a directory");
                    }
                    else {
                        looseApp.getConfig().addFile(parent, artifact.getFile(), dir + artifact.getFile().getName(), copyLibsDirectory);
                    }
                }
                else {
                    looseApp.getConfig().addFile(parent, artifact.getFile(), dir + artifact.getFile().getName());
                }
            }
        }
    }

    private boolean containsJavaSource(MavenProject proj) {
        List<String> srcDirs = proj.getCompileSourceRoots();
        for (String dir : srcDirs) {
            File javaSourceDir = new File(dir);
            if (javaSourceDir.exists() && javaSourceDir.isDirectory() && containsJavaSource(javaSourceDir)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsJavaSource(File dir) {
        File[] files = dir.listFiles();
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".java")) {
                return true;
            } else if (file.isDirectory()) {
                return containsJavaSource(file);
            }
        }
        return false;
    }

    // get loose application configuration file name for project artifact
    protected String getLooseConfigFileName(MavenProject project) {
        return getAppFileName(project) + ".xml";
    }

    // get loose application configuration file name for project artifact
    protected String getAppFileName(MavenProject project) {
        String name = project.getBuild().getFinalName() + "." + project.getPackaging();
        if (project.getPackaging().equals("liberty-assembly")) {
            name = project.getBuild().getFinalName() + ".war";
        }
        if (stripVersion) {
            name = stripVersionFromName(name, project.getVersion());
        }
        return name;
    }

    protected void validateAppConfig(String fileName, String artifactId) throws Exception {
        validateAppConfig(fileName, artifactId, false);
    }

    protected void validateAppConfig(String fileName, String artifactId, boolean isSpringBootApp) throws Exception {
        String appsDir = getAppsDirectory();
        if (appsDir.equalsIgnoreCase("apps") && !isAppConfiguredInSourceServerXml(fileName)) {
            // add application configuration
            applicationXml.createApplicationElement(fileName, artifactId, isSpringBootApp);
        } else if (appsDir.equalsIgnoreCase("dropins") && isAppConfiguredInSourceServerXml(fileName))
            throw new MojoExecutionException(messages.getString("error.install.app.dropins.directory"));
    }

    /**
     * Executes the SpringBootUtilTask to thin the Spring Boot application
     * executable archive and place the thin archive and lib.index.cache in the
     * specified location.
     * 
     * @param installDirectory
     *            : Installation directory of Liberty profile.
     * @param fatArchiveSrcLocation
     *            : Source Spring Boot FAT executable archive location.
     * @param thinArchiveTargetLocation
     *            : Target thin archive location.
     * @param libIndexCacheTargetLocation
     *            : Library cache location.
     */
    protected void invokeSpringBootUtilCommand(File installDirectory, String fatArchiveSrcLocation,
            String thinArchiveTargetLocation, String libIndexCacheTargetLocation) throws Exception {
        SpringBootUtilTask springBootUtilTask = (SpringBootUtilTask) ant
                .createTask("antlib:io/openliberty/tools/ant:springBootUtil");
        if (springBootUtilTask == null) {
            throw new IllegalStateException(
                    MessageFormat.format(messages.getString("error.dependencies.not.found"), "springBootUtil"));
        }

        Validate.notNull(fatArchiveSrcLocation, "Spring Boot source archive location cannot be null");
        Validate.notNull(thinArchiveTargetLocation, "Target thin archive location cannot be null");
        Validate.notNull(libIndexCacheTargetLocation, "Library cache location cannot be null");

        springBootUtilTask.setInstallDir(installDirectory);
        springBootUtilTask.setTargetThinAppPath(thinArchiveTargetLocation);
        springBootUtilTask.setSourceAppPath(fatArchiveSrcLocation);
        springBootUtilTask.setTargetLibCachePath(libIndexCacheTargetLocation);
        springBootUtilTask.execute();
    }

    protected boolean matches(Artifact artifact, ArtifactItem assemblyArtifact) {
        return artifact.getGroupId().equals(assemblyArtifact.getGroupId())
                && artifact.getArtifactId().equals(assemblyArtifact.getArtifactId())
                && artifact.getType().equals(assemblyArtifact.getType());
    }
    
    protected boolean isSupportedType(String type) {
        boolean supported = false;
        switch (type) {
            case "ear":
            case "war":
            case "rar":
            case "eba":
            case "esa":
            case "liberty-assembly":
                supported = true;
                break;
            default:
                break;
        }
        return supported;
    }

    public static boolean isSupportedLooseAppType(String type) {
        boolean supported = false;
        switch (type) {
            case "ear":
            case "war":
            case "liberty-assembly":
                supported = true;
                break;
            default:
                break;
        }
        return supported;
    }

}
