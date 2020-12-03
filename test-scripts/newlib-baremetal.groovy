// import the cheribuildProject() step
library 'ctsrd-jenkins-scripts'

cheribuildProject(
        target: "newlib-baremetal",
        targetArchitectures: ["mips64", "mips64-purecap", "riscv64", "riscv64-purecap"],
        sdkCompilerOnly: true, // No need for a CheriBSD sysroot
        extraArgs: '--install-prefix=/')