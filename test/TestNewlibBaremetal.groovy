import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TestNewlibBaremetal extends BasePipelineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'vars'
        super.setUp()
        // String sharedLibs = this.class.getResource('vars').getFile()
        /* def library = library().name('cheriHardwareTest')
                .defaultVersion("master")
                .allowOverride(true)
                .implicit(true)
                .targetPath(folder.root.getAbsolutePath())
                .retriever(localSource('vars'))
                .build()
        helper.registerSharedLibrary(library) */
        helper.registerAllowedMethod("timeout", [Integer.class, Closure.class], null)
        helper.registerAllowedMethod("timeout", [Map.class, Closure.class], null)
        helper.registerAllowedMethod("git", [String.class], null)
        helper.registerAllowedMethod("ansiColor", [String.class, Closure.class], null)
        helper.registerAllowedMethod("copyArtifacts", [Map.class], /*{ args -> println "Copying $args" }*/null)
        binding.setVariable("env", ["JOB_NAME":"newlib-baremetal/master"])
        // binding.getVariable('env').JOB_NAME = "CHERI1-TEST-pipeline"
		// def realCheribuildProject = cheribuildProject
        // helper.registerAllowedMethod("cheribuildProject", [String.class, Map.class], { t, args -> new cheribuildProject().call(t, args) })
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
    void should_execute_without_errors() throws Exception {
        // def script = loadScript("test-scripts/newlib-baremetal.groovy")
        def script = loadScript("vars/cheribuildProject.groovy")
        script.run()
        printCallStack()
    }
}