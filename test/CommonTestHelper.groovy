import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.rules.TemporaryFolder

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.LocalSource.localSource
import static com.lesfurets.jenkins.unit.global.lib.ProjectSource.projectSource

class CommonTestHelper {
    static class DockerMock {
        class Image {
            String name;
            public Image(String name) { this.name = name; }
            public void pull() { println("docker.pull " + this.name); }
            public void inside(String args, Closure C) {
                C();
            }
        }

        public Image image(String name) {
            println()
            return new Image(name)
        }
    }

//    static def withEnvInterceptor = { list, closure ->
//        oldEnv = binding.getVariable("env")
//        newEnv = oldEnv.clone()
//        list.forEach {
//            parts = it.split('=')
//            assert (parts.length == 2)
//            newEnv[parts[0]] = parts[1]
//            // println("env[${parts[0]}] = ${parts[1]}")
//        }
//        binding.setVariable("env", newEnv)
//        def res = closure.call()
//        binding.setVariable("env", oldEnv)
//        return res
//    }

    static Map doCheckout(Map args) {
        String url = args.getOrDefault("url", null)
        println(args)
        if (!url) {
            // Sometimes it's nested in an scm node
            def scmMap = args.getOrDefault("scm", args)
            for (config in scmMap.getOrDefault("userRemoteConfigs", [])) {
                url = config.getOrDefault("url", null)
                if (url)
                    break;
            }
        }
        if (!url) {
            throw new RuntimeException("Could not detect GIT URL ${args}")
        }
        return [GIT_URL:"xxx/xxxx.git", GIT_COMMIT:"abcdef123456"]
    }

    static void setupTestEnv(TemporaryFolder folder, BasePipelineTest test) {
        def helper = test.helper
        def binding = test.binding
        def library = library().name('ctsrd-jenkins-scripts')
                .defaultVersion("master")
                .allowOverride(true)
                .implicit(false)
                .targetPath(folder.root.getAbsolutePath())
                // FIXME: doesn't work .retriever(projectSource('vars'))
                .retriever(localSource('build/libs/'))
                .build()
        helper.registerSharedLibrary(library)
        helper.registerAllowedMethod("timeout", [Integer.class, Closure.class], null)
        helper.registerAllowedMethod("timeout", [Map.class, Closure.class], null)
        helper.registerAllowedMethod("culprits", [], null)
        helper.registerAllowedMethod("catchError", [Closure.class], { Closure c ->
            try {
                c.delegate = delegate
                helper.callClosure(c)
            } catch (ignored) {
                binding.getVariable('currentBuild').result = 'FAILURE'
            }
        })
        helper.registerAllowedMethod("warnError", [String, Closure], { String s, Closure c ->
            try {
                c.delegate = delegate
                helper.callClosure(c)
            } catch (ignored) {
                echo("Warning: ${s}")
                binding.getVariable('currentBuild').result = 'UNSTABLE'
            }
        })
        // Properties() helpers
        helper.registerAllowedMethod("compressBuildLog", { -> ["compressBuildLog"] })
        helper.registerAllowedMethod("disableConcurrentBuilds", { -> ["disableConcurrentBuilds"] })
        helper.registerAllowedMethod("rateLimitBuilds", [Map.class], { args -> ["rateLimitBuilds": args] })
        helper.registerAllowedMethod("copyArtifactPermission", [String.class], { arg -> ["copyArtifactPermission": arg] })
        helper.registerAllowedMethod("durabilityHint", [String.class], { arg -> ["durabilityHint": arg] })
        helper.registerAllowedMethod("disableResume", { -> ["disableResume"] })
        helper.registerAllowedMethod("githubPush", { -> ["githubPush"] })

        helper.registerAllowedMethod("brokenBuildSuspects", [], null)
        helper.registerAllowedMethod("brokenTestsSuspects", [], null)
        helper.registerAllowedMethod("issueCommentTrigger", [String], null)
        helper.registerAllowedMethod("requestor", [], null)
        helper.registerAllowedMethod("emailext", [Map.class], null)
        helper.registerAllowedMethod("pollSCM", [String.class], null)
        helper.registerAllowedMethod("lastSuccessful", [], null)
        helper.registerAllowedMethod("deleteDir", [], null)
        helper.registerAllowedMethod("checkout", [Map.class],
                { args -> doCheckout(args) })
        helper.registerAllowedMethod("junit", [Map.class], { args -> [totalCount: 1234, failCount: 1, skipCount: 5, passCount: 1229] })
        helper.registerAllowedMethod("timestamps", [Closure.class], null)
        helper.registerAllowedMethod("ansiColor", [String.class, Closure.class], null)
        helper.registerAllowedMethod("copyArtifacts", [Map.class], /*{ args -> println "Copying $args" }*/null)
        helper.registerAllowedMethod("warnings", [Map.class], /*{ args -> println "Copying $args" }*/null)
        helper.registerAllowedMethod("recordIssues", [Map.class], /*{ args -> println "Copying $args" }*/null)
        helper.registerAllowedMethod("clang", [], { args -> ["clang"]})
        helper.registerAllowedMethod("clang", [Map.class], { args -> ["clang"]})
        helper.registerAllowedMethod("git", [String.class], { url -> [GIT_URL:url, GIT_COMMIT:"abcdef123456"] })
        helper.registerAllowedMethod("git", [Map.class], { args -> [GIT_URL:args.url, GIT_COMMIT:"abcdef123456"] })
        helper.registerAllowedMethod("githubNotify", [Map.class], null)
        //FIXME: work around bug
        helper.registerAllowedMethod("dir", [String, Closure]) { String path, Closure c ->
            c.delegate = delegate
            helper.callClosure(c)
        }
        // binding.getVariable('env').JOB_NAME = "CHERI1-TEST-pipeline"
        // helper.registerAllowedMethod("cheriHardwareTest", [Map.class], { args -> cheriHardwareTest.call(args) })
        def scmBranch = "feature_test"
        binding.setVariable('scm', [branch: 'master', url: 'https://www.github.com/CTSRD-CHERI/some-repo.git'])
        binding.setVariable('docker', new DockerMock())
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
        binding.setVariable('env', [NODE_LABELS: "linux14 linux docker", UNIT_TEST: "true", CHANGE_ID: null])
        // Override the default helper
        helper.registerAllowedMethod("error", [String], { msg ->
            println(msg)
            binding.getVariable('currentBuild').result = 'FAILURE'
            throw new RuntimeException(msg)
        })
        helper.registerAllowedMethod("unstable", [String.class], null)
        // binding.setVariable('currentBuild', [result: null, currentResult: 'SUCCESS', durationString: "XXX seconds"])
    }

    static void addEnvVars(BasePipelineTest test, Map<String, String> vars) {
        def x = test.binding.getVariable("env")
        // print("Before: ")
        // println(x)
        x << vars
        x = test.binding.getVariable("env")
        // print("After: ")
        // println(x)
    }

}
