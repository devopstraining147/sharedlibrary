def info(Object m) {
    ansiColor('xterm') {
        echo "\033[1mINFO: ${String.valueOf(m)}"
    }
}

def warn(Object m) {
    ansiColor('xterm') {
        echo "\033[1;31mWARNING: ${String.valueOf(m)}"
    }
}

def error(Object m) {
    ansiColor('xterm') {
        echo "\033[1;31mERROR: ${String.valueOf(m)}"
    }
}

return this