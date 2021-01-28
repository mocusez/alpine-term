PACKAGE_VERSION="7.73.0"
PACKAGE_SRCURL="https://curl.haxx.se/download/curl-${PACKAGE_VERSION}.tar.bz2"
PACKAGE_SHA256="cf34fe0b07b800f1c01a499a6e8b2af548f6d0e044dca4a29d88a4bee146d131"
PACKAGE_DEPENDS="nghttp2, openssl, zlib"
PACKAGE_EXTRA_CONFIGURE_ARGS="
--enable-ntlm-wb=/system/bin/ntlm_auth
--with-nghttp2
--with-ssl
--with-ca-bundle=$PACKAGE_INSTALL_PREFIX/ca-certificates.pem
"

builder_step_pre_configure() {
	rm -f CMakeLists.txt
}
