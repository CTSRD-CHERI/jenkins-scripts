
def buildProjectWithCheribuild(projectName, extraArgs, String targetCPU, Map otherArgs) {
    def sdkCPU = targetCPU
    if (sdkCPU.startsWith("hybrid-")) {
        sdkCPU = sdkCPU.substring("hybrid-".length())
    }
    def tarballName = otherArgs.get('tarballName', "${projectName}-${targetCPU}.tar.xz")
    echo "Target CPU: ${targetCPU}, SDK CPU: ${sdkCPU}, output: ${tarballName}"
    // build steps that should happen on all nodes go here
    def sdkImage = docker.image("ctsrd/cheri-sdk-${sdkCPU}:latest")
    sdkImage.pull() // make sure we have the latest available from Docker Hub
    stage("build ${targetCPU}") {
        dir(projectName) {
            checkout scm
        }
        dir ('cheribuild') {
            git 'https://github.com/CTSRD-CHERI/cheribuild.git'
        }
        sdkImage.inside('-u 0') {
            env.CPU = targetCPU
            ansiColor('xterm') {
                sh '''
                    env
                    pwd
                   '''
                sh "./cheribuild/jenkins-cheri-build.py --build ${projectName} --cpu ${targetCPU} ${extraArgs}"
                if (otherArgs.beforeTarball) {
                    sh otherArgs.beforeTarball
                }
                sh "./cheribuild/jenkins-cheri-build.py --tarball --tarball-name ${tarballName} --no-build ${projectName} --cpu ${targetCPU} ${extraArgs}"
            }
        }
        sh 'ls -la'
        archiveArtifacts allowEmptyArchive: false, artifacts: tarballName, fingerprint: true, onlyIfSuccessful: true
    }
    if ('testScript' in args) {
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
            def cheribsdImage = docker.image("ctsrd/${imageName}:latest")
            cheribsdImage.inside('-u 0') {
                // ./boot_cheribsd.py --qemu-cmd ~/cheri/output/sdk256/bin/qemu-system-cheri --disk-image ./cheribsd-jenkins_bluehive.img.xz --kernel cheribsd-cheri-malta64-kernel.bz2 -i
                // TODO: allow booting the minimal bluehive disk-image
                testCommand = "'export CPU=${targetCPU}; " + args.testScript.replaceAll('\'', '\\\'') + "'"

                sh "boot_cheribsd.py --test-command ${testCommand} --test-archive ${tarballName} --test-timeout ${testTimeout}"
            }
        }
    }

// This is what get's called from jenkins
def call(Map args) {
    def targets
    if ("targets" in args) {
        targets = args.targets
    } else {
        targets = ['cheri256', 'cheri128', 'mips', 'hybrid-cheri128']
    }
    Map<String, Closure> jobs = targets.collectEntries {
        [(it): {
            node('docker') {
                echo "Building for ${it}"
                buildProjectWithCheribuild(args.name, args.extraArgs, it, args)
            }
        }]
    }
    parallel jobs
}