import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Test


import com.lesfurets.jenkins.unit.cps.BasePipelineTestCPS

class TestParallelJobCPS extends BasePipelineTestCPS {

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
        helper.registerAllowedMethod("timeout", [Integer.class, Closure.class], null)
        helper.registerAllowedMethod("timeout", [Map.class, Closure.class], null)
        helper.registerAllowedMethod("copyArtifacts", [Map.class], { args -> println "Copying $args" })
        def scmBranch = "feature_test"
        binding.setVariable('scm', [
                $class                           : 'GitSCM',
                branches                         : [[name: scmBranch]],
                doGenerateSubmoduleConfigurations: false,
                extensions                       : [],
                submoduleCfg                     : [],
                userRemoteConfigs                : [[
                                                            credentialsId: 'gitlab_git_ssh',
                                                            url          : 'github.com/lesfurets/JenkinsPipelineUnit.git'
                                                    ]]
        ])
    }

    @Test
    void should_execute_without_errors() throws Exception {
        def script = loadScript("vars/cheritest.groovy")
        script.run()
        printCallStack()
    }
}