
// TODO: make this a class?

// Run a beforeXXX hook (beforeBuild, beforeTarball, etc.)
def runCallback(String hook, Map args) {
    def cb = args.get(hook)
    if (!cb) {
        return
    }
    echo "Running callback ${hook}"
    if ((cb instanceof Closure) || cb.metaClass.respondsTo(cb, 'call')) {
        cb(args.cpu)
    } else {
        assert cb instanceof String
        if (!cb.allWhitespace) {
            sh cb
        }
    }
}


def buildProjectWithCheribuild(projectName, extraArgs, String targetCPU, Map otherArgs) {
    def sdkCPU = targetCPU
    otherArgs.cpu = targetCPU
    if (sdkCPU.startsWith("hybrid-")) {
        sdkCPU = sdkCPU.substring("hybrid-".length())
    }
    def tarballName = otherArgs.get('tarballName', "${projectName}-${targetCPU}.tar.xz")
    // build steps that should happen on all nodes go here
    stage("Build ${targetCPU}") {
        echo "Target CPU: ${targetCPU}, SDK CPU: ${sdkCPU}, output: ${tarballName}"
        def sdkImage = docker.image("ctsrd/cheri-sdk-${sdkCPU}:latest")
        sdkImage.pull() // make sure we have the latest available from Docker Hub
        runCallback('beforeSCM', otherArgs)
        dir(projectName) {
            checkout scm
        }
        dir('cheribuild') {
            git 'https://github.com/CTSRD-CHERI/cheribuild.git'
        }
        runCallback('beforeBuildOutsideDocker', otherArgs)
        sdkImage.inside('-u 0') {
            env.CPU = targetCPU
            ansiColor('xterm') {
                sh "rm -fv ${tarballName}; pwd"
                runCallback('beforeBuild', otherArgs)
                def cheribuildCmd = "./cheribuild/jenkins-cheri-build.py --build ${projectName} --cpu ${targetCPU} ${extraArgs}"
                // by default try an incremental build first and if that fails fall back to a clean build
                // this behaviour can be disabled by passing noIncrementalBuild: true
                if (otherArgs.noIncrementalBuild) {
                    sh "${cheribuildCmd}"
                } else {
                    sh "${cheribuildCmd} --no-clean || (echo 'incremental build failed!' && ${cheribuildCmd})"
                }
                runCallback('beforeTarball', otherArgs)
                sh "./cheribuild/jenkins-cheri-build.py --tarball --tarball-name ${tarballName} --no-build ${projectName} --cpu ${targetCPU} ${extraArgs}"
            }
        }
        sh 'ls -la'
        archiveArtifacts allowEmptyArchive: false, artifacts: tarballName, fingerprint: true, onlyIfSuccessful: true
        runCallback('afterBuild', otherArgs)
    }
    if ('testScript' in otherArgs) {
        def testTimeout = otherArgs.get('testTimeout', 60 * 60)
        stage("run tests for ${targetCPU}") {
            def imageName
            if (targetCPU == 'mips') {
                imageName = 'cheri256-cheribsd-mips'
            } else {
                // boot a world with a hybrid userspace (it contains all the necessary shared libs)
                // There is no need for the binaries to be CHERIABI
                imageName = "${sdkCPU}-cheribsd-hybrid"
            }
            def testImageArg = ''
            if (otherArgs.minimalTestImage) {
                testImageArg = "--disk-image /usr/local/share/cheribsd/cheribsd-minimal.img"
            }
            def cheribsdImage = docker.image("ctsrd/${imageName}:latest")
            cheribsdImage.pull()
            runCallback('beforeTestsOutsideDocker', otherArgs)
            cheribsdImage.inside('-u 0') {
                // ./boot_cheribsd.py --qemu-cmd ~/cheri/output/sdk256/bin/qemu-system-cheri --disk-image ./cheribsd-jenkins_bluehive.img.xz --kernel cheribsd-cheri-malta64-kernel.bz2 -i
                // TODO: allow booting the minimal bluehive disk-image
                def testCommand = "'export CPU=${targetCPU}; " + otherArgs.testScript.replaceAll('\'', '\\\'') + "'"
                ansiColor('xterm') {
                    sh "wget https://raw.githubusercontent.com/RichardsonAlex/cheri-sdk-docker/master/cheribsd/boot_cheribsd.py -O /usr/local/bin/boot_cheribsd.py"
                    if ('beforeTests' in otherArgs) {
                        otherArgs.beforeTests(targetCPU)
                    }
                    def testExtraArgs = otherArgs.get('testExtraArgs', '')
                    sh "boot_cheribsd.py ${testImageArg} --test-command ${testCommand} --test-archive ${tarballName} --test-timeout ${testTimeout} ${testExtraArgs}"
                }
            }
        }
        runCallback('afterTests', otherArgs)
    }
    // TODO: clean up properly and remove the created artifacts?
}

// This is what get's called from jenkins
def call(Map args) {
    def targets = args.get('targets', ['mips', 'cheri256', 'cheri128', 'hybrid-cheri128'])
    Map<String, Closure> jobs = targets.collectEntries {
        [(it): {
            node('docker') {
                buildProjectWithCheribuild(args.name, args.extraArgs, it, args)
            }
        }]
    }
    if (args.sequential) {
        jobs.each { k, job -> job() }
    } else {
        stage("Cheribuild") {
            parallel jobs
        }
    }
}