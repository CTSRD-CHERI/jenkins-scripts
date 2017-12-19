import com.lesfurets.jenkins.unit.BasePipelineTest
import com.lesfurets.jenkins.unit.cps.BasePipelineTestCPS
import com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration
import com.lesfurets.jenkins.unit.global.lib.LocalSource
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
		String sharedLibs = "."
        def library = LibraryConfiguration.library().name('jenkins-scripts')
                .defaultVersion("master")
                .allowOverride(false)
                .implicit(true)
                .targetPath(sharedLibs)
                .retriever(LocalSource.localSource(sharedLibs))
                .build()
        // helper.registerSharedLibrary(library)
		setScriptRoots([ 'src', 'vars', 'test/groovy', '.' ] as String[])
		setScriptExtension('groovy')
        helper.registerAllowedMethod("timeout", [Integer.class, Closure.class], null)
        helper.registerAllowedMethod("timeout", [Map.class, Closure.class], null)
        helper.registerAllowedMethod("git", [String.class], null)
        helper.registerAllowedMethod("ansiColor", [String.class, Closure.class], null)
        helper.registerAllowedMethod("copyArtifacts", [Map.class], /*{ args -> println "Copying $args" }*/null)
        helper.registerAllowedMethod("warnings", [Map.class], /*{ args -> println "Copying $args" }*/null)
        binding.setVariable("env", ["JOB_NAME":"newlib-baremetal/master", "RUN_UNIT_TESTS": "1"])
        // binding.getVariable('env').JOB_NAME = "CHERI1-TEST-pipeline"
		// def realCheribuildProject = cheribuildProject
        // helper.registerAllowedMethod("cheribuildProject", [Map.class], {args -> cheribuildProject(args)})
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
        def script = loadScript("cheribuildProject.groovy")
		script.run()
        // script.evaluate("test-scripts/newlib-baremetal.groovy" as File)
        printCallStack()
    }
}