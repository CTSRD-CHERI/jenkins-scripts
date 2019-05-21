def call(Map args) {
    def labels = "${env.NODE_LABELS}"
    echo("inferring build OS, node labels: ${labels}")
    if (labels.contains("linux"))
        return "linux"
    if (labels.contains("freebsd"))
        return "freebsd"
    error("Could not determine node label from '${env.NODE_LABELS}'")
    return "UNKNOWN BUILD OS"
}
