// Wrapper JNI de tun2socks (xjasonlyu, gvisor) compilado como -buildmode=c-shared.
//
// Android 10+ bloquea por SELinux ejecutar binarios en el almacenamiento privado
// de la app (EACCES "Permission denied"), asi que en vez de un proceso externo
// cargamos la libreria con dlopen (= System.loadLibrary) y arrancamos el stack
// de gvisor "en proceso".
//
// El socketpair SOCK_DGRAM lo crea Go (los numeros de fd del lazo Java<->Go):
//  - un extremo se queda en Go como "device" de tun2socks (-device fd://N)
//  - el otro vuelve a Java para que la app siga bombeando los paquetes y pueda
//    interceptar el DNS (UDP:53 -> DNS over TCP via SOCKS).
//
// Los nombres de export siguen la convencion JNI de
// com.proxianon.app.vpn.Tun2SocksProcess.
package main

/*
#include <stdint.h>
#include <stddef.h>
*/
import "C"

import (
	"bytes"
	"fmt"
	"os"
	"sync"
	"unsafe"

	"github.com/xjasonlyu/tun2socks/v2/engine"
	_ "github.com/xjasonlyu/tun2socks/v2/dns"

	"golang.org/x/sys/unix"
)

var (
	engineStarted = false
	fdGo          = -1 // extremo del socketpair que usa tun2socks como device
	errRedirect   sync.Once
)

// Redirige stderr/stdout (a nivel de fd) a un archivo dentro del filesDir.
// El log.Fatal de engine.Start hace os.Exit(1) y escribe por stderr: con dup2
// lo capturamos aunque el logger haya cacheado el descriptor en su init.
func redirectGoLogs() {
	pkg, err := currentPackageName()
	if err != nil {
		return
	}
	dir := "/data/user/0/" + pkg + "/files"
	_ = os.MkdirAll(dir, 0o755)
	f, err := os.Create(dir + "/tun2socks_err.log")
	if err != nil {
		return
	}
	_ = unix.Dup2(int(f.Fd()), 2) // stderr -> archivo
	_ = unix.Dup2(int(f.Fd()), 1) // stdout -> archivo
	os.Stdout = f
	os.Stderr = f
}

// Como la lib corre en el proceso de la app, /proc/self/cmdline = nombre del paquete.
func currentPackageName() (string, error) {
	b, err := os.ReadFile("/proc/self/cmdline")
	if err != nil {
		return "", err
	}
	if i := bytes.IndexByte(b, 0); i >= 0 {
		b = b[:i]
	}
	return string(b), nil
}

// Crea el socketpair y devuelve el fd del lado de la APP (el de Go se queda aqui).
// OJO con JNI: toda funcion nativa recibe (JNIEnv*, jobject) como primeros
// argumentos aunque Kotlin no los declare; sin ellos los parametros se desplazan.
//
//export Java_com_proxianon_app_vpn_Tun2SocksProcess_tun2socksOpenPair
func Java_com_proxianon_app_vpn_Tun2SocksProcess_tun2socksOpenPair(env, thiz unsafe.Pointer) C.int {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		return C.int(-1)
	}
	fdGo = fds[1]
	return C.int(fds[0])
}

// Arranca el stack de gvisor usando el fd de Go como "device" y SOCKS5 local.
// (env, thiz) son los argumentos ocultos de JNI; los reales vienen a continuacion.
//
//export Java_com_proxianon_app_vpn_Tun2SocksProcess_tun2socksStart
func Java_com_proxianon_app_vpn_Tun2SocksProcess_tun2socksStart(env, thiz unsafe.Pointer, socksPort, mtu C.int) C.int {
	if engineStarted {
		return 0
	}
	if fdGo < 0 {
		return C.int(-2)
	}
	errRedirect.Do(redirectGoLogs) // captura el log.Fatal del engine en un archivo
	key := new(engine.Key)
	key.Device = fmt.Sprintf("fd://%d", fdGo)
	key.Proxy = fmt.Sprintf("socks5://127.0.0.1:%d", int(socksPort))
	key.MTU = int(mtu)
	key.LogLevel = "info"
	engine.Insert(key)
	engine.Start() // (v2.7.0: no devuelve error; log.Fatal -> exit(1))
	engineStarted = true
	return 0
}

// Detiene el stack y cierra el fd.
//
//export Java_com_proxianon_app_vpn_Tun2SocksProcess_tun2socksStop
func Java_com_proxianon_app_vpn_Tun2SocksProcess_tun2socksStop(env, thiz unsafe.Pointer) {
	if engineStarted {
		engine.Stop() // cierra el device (y el fd go del socketpair) internamente
		engineStarted = false
	}
	fdGo = -1 // NO cerrar de nuevo: engine.Stop ya cerro fdGo; el runtime puede
	// haber reutilizado el numero de fd y un unix.Close aqui cerraria algo ajeno.
}

func main() {}