import com.lesfurets.jenkins.unit.BasePipelineTest
import com.lesfurets.jenkins.unit.MethodSignature
import com.lesfurets.jenkins.unit.PipelineTestHelper
import org.junit.rules.TemporaryFolder

import static com.lesfurets.jenkins.unit.MethodSignature.method
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
        if (args.get('$class', null) == 'SubversionSCM') {
            println("SVN repo: ${args}")
            return ["SVN": "not supported"]
        }
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
        return [GIT_URL:url, GIT_COMMIT:"abcdef123456"]
    }

    static void registerMethod(PipelineTestHelper helper, String name, Closure closure = null) {
        registerMethod(helper, name, [], closure)
    }
    static void registerMethod(PipelineTestHelper helper, String name, List<Class> args, Closure closure = null) {
        def found = helper.allowedMethodCallbacks.findAll {it.key.getName() == name }
        for (it in found) {
            if (it.key.getArgs() == args.toArray())
                throw new IllegalArgumentException("Method already registered: ${it}")
            else
                println("Method ${name}(${args}) already registered with different args: ${it}")
        }
        helper.registerAllowedMethod(name, args, closure)
    }
    // Replace the implementation from com.lesfurets.jenkins.unit.BasePipelineTest
    static void registerMethodOverride(PipelineTestHelper helper, String name, List<Class> args, Closure closure = null) {
        helper.registerAllowedMethod(name, args, closure)
    }

    static void setupTestEnv(TemporaryFolder folder, BasePipelineTest test, String testName) {
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

        // helper.allowedMethodCallbacks.each { it -> println("${it.key}") }
        helper.registerSharedLibrary(library)
        registerMethod(helper, "timeout", [Integer.class, Closure.class], { Integer time, Closure c ->
            c.delegate = delegate
            helper.callClosure(c)
        })
        registerMethod(helper, "lock", [String.class, Closure.class], null)
        registerMethod(helper, "culprits", [], null)
        registerMethod(helper, "catchError", [Closure.class], { Closure c ->
            try {
                c.delegate = delegate
                helper.callClosure(c)
            } catch (ignored) {
                binding.getVariable('currentBuild').result = 'FAILURE'
            }
        })
        // Properties() helpers
        registerMethod(helper, "compressBuildLog", { -> ["compressBuildLog"] })
        registerMethod(helper, "rateLimitBuilds", [Map.class], { args -> ["rateLimitBuilds": args] })
        registerMethod(helper, "copyArtifactPermission", [String.class], { arg -> ["copyArtifactPermission": arg] })
        registerMethod(helper, "durabilityHint", [String.class], { arg -> ["durabilityHint": arg] })
        registerMethod(helper, "disableResume", { -> ["disableResume"] })
        registerMethod(helper, "githubPush", { -> ["githubPush"] })

        registerMethod(helper, "brokenBuildSuspects", [], null)
        registerMethod(helper, "brokenTestsSuspects", [], null)
        registerMethod(helper, "issueCommentTrigger", [String], null)
        registerMethod(helper, "requestor", [], null)
        registerMethod(helper, "emailext", [Map.class], null)
        registerMethod(helper, "lastSuccessful", [], null)
        registerMethodOverride(helper, "checkout", [Map.class],
                { args -> doCheckout(args) })
        registerMethod(helper, "junit", [Map.class], { args -> [totalCount: 1234, failCount: 1, skipCount: 5, passCount: 1229] })
        registerMethod(helper, "timestamps", [Closure.class], null)
        registerMethod(helper, "ansiColor", [String.class, Closure.class], null)
        registerMethod(helper, "warnings", [Map.class], /*{ args -> println "Copying $args" }*/null)
        registerMethod(helper, "recordIssues", [Map.class], /*{ args -> println "Copying $args" }*/null)
        registerMethod(helper, "clang", [], { args -> ["clang"]})
        registerMethod(helper, "clang", [Map.class], { args -> ["clang"]})
        registerMethod(helper, "git", [String.class], { url -> [GIT_URL:url, GIT_COMMIT:"abcdef123456"] })
        registerMethod(helper, "git", [Map.class], { args -> [GIT_URL:args.url, GIT_COMMIT:"abcdef123456"] })
        registerMethod(helper, "githubNotify", [Map.class], null)
        // binding.getVariable('env').JOB_NAME = "CHERI1-TEST-pipeline"
        // registerMethod(helper, "cheriHardwareTest", [Map.class], { args -> cheriHardwareTest.call(args) })
        def scmBranch = "feature_test"
        binding.setVariable('scm', [branch: 'master', url: 'https://www.github.com/CTSRD-CHERI/' + testName + '.git'])
        binding.setVariable('docker', new DockerMock())
        test.addEnvVar('NODE_LABELS', 'linux14 linux docker')
        test.addEnvVar('UNIT_TEST', 'true')
        test.addEnvVar('CHANGE_ID', null)

        // Override the default helper to ensure the exception is raised
        registerMethodOverride(helper, "error", [String], { msg ->
            test.updateBuildStatus('FAILURE')
            throw new RuntimeException(msg)
        })
    }

    static void addEnvVars(BasePipelineTest test, Map<String, String> vars) {
        def x = test.binding.getVariable("env")
        String jobName = vars.getOrDefault('JOB_NAME', '')
        if (jobName.contains('/')) {
            vars.putIfAbsent('JOB_BASE_NAME', jobName.substring(jobName.indexOf('/') + 1))
        }
        // print("Before: ")
        // println(x)
        x << vars
        x = test.binding.getVariable("env")
        // print("After: ")
        // println(x)
    }

}
