@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'day', userBoost: true]),
        copyArtifactPermission('*'),
])

def applyPatches() {
    stage("Apply patches") {
        sh label: "Apply Bluespec DM workarounds", script: """
cd riscv-openocd && patch -p1 << "EOF"
diff --git a/src/target/riscv/riscv-013.c b/src/target/riscv/riscv-013.c
index f2c467618..9422a9513 100644
--- a/src/target/riscv/riscv-013.c
+++ b/src/target/riscv/riscv-013.c
@@ -1836,7 +1850,8 @@ static int reset_dm(struct target *target)
 				LOG_TARGET_ERROR(target, "DM didn't acknowledge reset in %d s. "
 						"Increase the timeout with 'riscv set_command_timeout_sec'.",
 						riscv_get_command_timeout_sec());
-				return ERROR_TIMEOUT_REACHED;
+				/* XXX: Bluespec's DM auto-sets `dmactive.dmactive` */
+				break; /* return ERROR_TIMEOUT_REACHED; */
 			}
 		} while (get_field32(dmcontrol, DM_DMCONTROL_DMACTIVE));
 		LOG_TARGET_DEBUG(target, "DM reset initiated.");
EOF
"""
    }
}

def buildNative(String name, String nodeLabel) {
    def riscvOpenocdRepo = gitRepoWithLocalReference(url: 'https://github.com/riscv-collab/riscv-openocd', branch: 'riscv')
    def extraArgs = [
            '--install-prefix=/',
    ]
    cheribuildProject(target: "riscv-openocd", architecture: "native",
                      scmOverride: riscvOpenocdRepo,
                      skipArchiving: false,
                      runTests: false,
                      setGitHubStatus: false, // external repo
                      tarballName: "riscv-openocd-${name}.tar.xz",
                      nodeLabel: nodeLabel,
                      sdkCompilerOnly: true,
                      beforeBuild: { proj -> applyPatches() },
                      extraArgs: extraArgs.join(" "),
    )
}

jobs = [:]

def allNativeBuilds = [
        'linux': 'linux-baseline',
        'linux-jammy': 'jammy',
        'linux-noble': 'noble',
        'freebsd': 'freebsd',
]
allNativeBuilds.each { osName, nodeLabel ->
    def name = 'native-' + osName
    jobs[name] = { ->
        buildNative(name, nodeLabel)
    }
}

parallel jobs
