PACKAGE_VERSION="1.7.3.4"
PACKAGE_SRCURL="http://www.dest-unreach.org/socat/download/socat-${PACKAGE_VERSION}.tar.gz"
PACKAGE_SHA256="d9ed2075abed7b3ec9730ed729b4c8e287c502181c806d4487020418a6e2fc36"
PACKAGE_BUILD_IN_SRC="true"

PACKAGE_EXTRA_CONFIGURE_ARGS="
--disable-stdio
--disable-fdnum
--disable-file
--disable-creat
--disable-rawip
--disable-genericsocket
--disable-interface
--disable-sctp
--disable-socks4
--disable-socks4a
--disable-proxy
--disable-exec
--disable-system
--disable-pty
--disable-ext2
--disable-readline
--disable-openssl
--disable-tun
--disable-sycls
--disable-filan
ac_header_resolv_h=no
ac_cv_c_compiler_gnu=yes
ac_compiler_gnu=yes
"

builder_step_post_make_install() {
	local bindir

	case "$PACKAGE_TARGET_ARCH" in
		aarch64) bindir="arm64-v8a";;
		arm) bindir="armeabi-v7a";;
		i686) bindir="x86";;
		x86_64) bindir="x86_64";;
		*) echo "Invalid architecture '$PACKAGE_TARGET_ARCH'" && return 1;;
	esac

	install -Dm700 "$PACKAGE_INSTALL_PREFIX"/bin/socat \
		"${BUILDER_SCRIPTDIR}/jniLibs/${bindir}/libsocat.so"
	"$STRIP" -s "${BUILDER_SCRIPTDIR}/jniLibs/${bindir}/libsocat.so"
}
