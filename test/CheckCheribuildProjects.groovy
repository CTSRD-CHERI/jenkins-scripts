import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.LocalSource.localSource

class CheckCheribuildProjects extends BasePipelineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'vars'
        super.setUp()
         // String sharedLibs = this.class.getResource('vars').getFile()
         def library = library().name('ctsrd-jenkins-scripts')
                .defaultVersion("master")
                .allowOverride(true)
                .implicit(false)
                .targetPath(folder.root.getAbsolutePath())
                .retriever(localSource('build/libs/'))
                .build()
        helper.registerSharedLibrary(library)
        helper.registerAllowedMethod("timeout", [Integer.class, Closure.class], null)
        helper.registerAllowedMethod("timeout", [Map.class, Closure.class], null)
        helper.registerAllowedMethod("disableResume", [], null)
        helper.registerAllowedMethod("githubPush", [], null)
        helper.registerAllowedMethod("deleteDir", [], null)
        helper.registerAllowedMethod("junit", [Map.class], null)
        helper.registerAllowedMethod("timestamps", [Closure.class], null)
        helper.registerAllowedMethod("ansiColor", [String.class, Closure.class], null)
        helper.registerAllowedMethod("copyArtifacts", [Map.class], /*{ args -> println "Copying $args" }*/null)
        helper.registerAllowedMethod("warnings", [Map.class], /*{ args -> println "Copying $args" }*/null)
        helper.registerAllowedMethod("git", [String.class], { url -> [GIT_URL:url, GIT_COMMIT:"abcdef123456"] })
        helper.registerAllowedMethod("git", [Map.class], { args -> [GIT_URL:args.url, GIT_COMMIT:"abcdef123456"] })
        // binding.getVariable('env').JOB_NAME = "CHERI1-TEST-pipeline"
        // helper.registerAllowedMethod("cheriHardwareTest", [Map.class], { args -> cheriHardwareTest.call(args) })
        def scmBranch = "feature_test"
        binding.setVariable('scm', [branch: 'master'])
        /* binding.setVariable('scm', [
                $class                           : 'GitSCM',
                branches                         : [[name: scmBranch]],
                doGenerateSubmoduleConfigurations: false,
                extensions                       : [],
                submoduleCfg                     : [],
                userRemoteConfigs                : [[
                                                            credentialsId: 'gitlab_git_ssh',
                                                            url          : 'github.com/lesfurets/JenkinsPipelineUnit.git'
                                                    ]]
        ]) */
    }

    @Test
    void qemu_test() throws Exception {
//        def script = loadScript("test-scripts/qemu.groovy")
//        script.run()
//        printCallStack()
        binding.setVariable("env", ["JOB_NAME":"QEMU/qemu-cheri"])
        def script = runScript("test-scripts/qemu.groovy")
        // script.run()
        printCallStack()
    }

	@Test
	void llvm_test() throws Exception {
		binding.setVariable("env", [
                JOB_NAME:"LLVM-linux/cap-table",
                BRANCH_NAME:"cap-table",
                WORKSPACE:"/workspace",
        ])
		def script = runScript("test-scripts/llvm.groovy")
		// script.run()
		printCallStack()
	}
}