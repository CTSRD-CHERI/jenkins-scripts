@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'day', userBoost: true]),
        copyArtifactPermission('*'),
])

def applyPatches() {
    stage("Apply patches") {
        sh label: "Apply Bluespec DM workarounds", script: """
cd riscv-openocd && patch -p1 << EOF
diff --git a/src/target/riscv/riscv-013.c b/src/target/riscv/riscv-013.c
index f2c467618..9422a9513 100644
--- a/src/target/riscv/riscv-013.c
+++ b/src/target/riscv/riscv-013.c
@@ -893,6 +893,7 @@ static int register_read_abstract_with_size(struct target *target,
 	if (number >= GDB_REGNO_V0 && number <= GDB_REGNO_V31)
 		return ERROR_FAIL;

+retry:;
 	uint32_t command = riscv013_access_register_command(target, number, size,
 			AC_ACCESS_REGISTER_TRANSFER);

@@ -904,6 +905,12 @@ static int register_read_abstract_with_size(struct target *target,
 				info->abstract_read_fpr_supported = false;
 				LOG_TARGET_INFO(target, "Disabling abstract command reads from FPRs.");
 			} else if (number >= GDB_REGNO_CSR0 && number <= GDB_REGNO_CSR4095) {
+				/* XXX: Bluespec's DM doesn't support 32-bit access on RV64 */
+				if (size == 32) {
+					LOG_TARGET_INFO(target, "Retrying 64-bit read of CSR %x", number - GDB_REGNO_CSR0);
+					size = 64;
+					goto retry;
+				}
 				info->abstract_read_csr_supported = false;
 				LOG_TARGET_INFO(target, "Disabling abstract command reads from CSRs.");
 			}
@@ -941,7 +948,8 @@ static int register_write_abstract(struct target *target, enum gdb_regno number,
 			!info->abstract_write_csr_supported)
 		return ERROR_FAIL;

-	const unsigned int size_bits = register_size(target, number);
+	unsigned int size_bits = register_size(target, number);
+retry:;
 	const uint32_t command = riscv013_access_register_command(target, number, size_bits,
 			AC_ACCESS_REGISTER_TRANSFER |
 			AC_ACCESS_REGISTER_WRITE);
@@ -971,6 +979,12 @@ static int register_write_abstract(struct target *target, enum gdb_regno number,
 				info->abstract_write_fpr_supported = false;
 				LOG_TARGET_INFO(target, "Disabling abstract command writes to FPRs.");
 			} else if (number >= GDB_REGNO_CSR0 && number <= GDB_REGNO_CSR4095) {
+				/* XXX: Bluespec's DM doesn't support 32-bit access on RV64 */
+				if (size_bits == 32) {
+					LOG_TARGET_INFO(target, "Retrying 64-bit write of CSR %x", number - GDB_REGNO_CSR0);
+					size_bits = 64;
+					goto retry;
+				}
 				info->abstract_write_csr_supported = false;
 				LOG_TARGET_INFO(target, "Disabling abstract command writes to CSRs.");
 			}
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
"""
    }
}

def fetchLibusbDev(proj) {
    stage("Fetch libusb-1.0-0-dev") {
        def libusbDevCheck = sh returnStatus: true, label: "Checking if libusb-1.0-0-dev is already present",
                                script: "dpkg-query -s libusb-1.0-0-dev"
        if (libusbDevCheck == 0) {
            echo "Skipping download"
        } else {
            sh label: "Fetch libusb-1.0-0-dev", script: """
cd $WORKSPACE
rm -f libusb-1.0-0-dev*.deb
rm -rf libusb-1.0-0-dev
apt-get download -y libusb-1.0-0-dev
mkdir libusb-1.0-0-dev
dpkg -x libusb-1.0-0-dev*.deb libusb-1.0-0-dev
arch="`dpkg-architecture -qDEB_HOST_MULTIARCH`"
link="libusb-1.0-0-dev/usr/lib/\$arch/libusb-1.0.so"
target="`readlink "\$link"`"
case "\$target" in
    '/'*)
        ;;
    *)
        ln -fs "/usr/lib/\$arch/\$target" "\$link"
        ;;
esac
sed -i "s,^prefix=/usr\$,prefix=$WORKSPACE/libusb-1.0-0-dev/usr," "libusb-1.0-0-dev/usr/lib/\$arch/pkgconfig/libusb-1.0.pc"
"""
            def LIBUSB1_CFLAGS = sh returnStdout: true, label: "Get libusb-1.0-0-dev CFLAGS", script: """
arch="`dpkg-architecture -qDEB_HOST_MULTIARCH`"
pkg-config --cflags "libusb-1.0-0-dev/usr/lib/\$arch/pkgconfig/libusb-1.0.pc"
"""
            def LIBUSB1_LIBS = sh returnStdout: true, label: "Get libusb-1.0-0-dev LIBS", script: """
arch="`dpkg-architecture -qDEB_HOST_MULTIARCH`"
pkg-config --libs "libusb-1.0-0-dev/usr/lib/\$arch/pkgconfig/libusb-1.0.pc"
"""
            LIBUSB1_CFLAGS = LIBUSB1_CFLAGS.trim()
            LIBUSB1_LIBS = LIBUSB1_LIBS.trim()
            if (!proj.extraArgs.isEmpty()) {
                proj.extraArgs += " "
            }
            proj.extraArgs += "--${proj.target}/configure-options='LIBUSB1_CFLAGS=\"${LIBUSB1_CFLAGS}\" LIBUSB1_LIBS=\"${LIBUSB1_LIBS}\"'"
        }
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
                      beforeBuild: { proj ->
                          applyPatches()
                          if (name == "native-linux") {
                              fetchLibusbDev(proj )
                          }
                      },
                      extraArgs: extraArgs.join(" "),
    )
}

jobs = [:]

def allNativeBuilds = [
        'linux': 'linux-baseline',
        'freebsd': 'freebsd',
]
allNativeBuilds.each { osName, nodeLabel ->
    def name = 'native-' + osName
    jobs[name] = { ->
        buildNative(name, nodeLabel)
    }
}

parallel jobs
