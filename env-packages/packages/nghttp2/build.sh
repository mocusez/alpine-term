PACKAGE_VERSION="1.41.0"
PACKAGE_SRCURL="https://github.com/nghttp2/nghttp2/releases/download/v${PACKAGE_VERSION}/nghttp2-${PACKAGE_VERSION}.tar.xz"
PACKAGE_SHA256="abc25b8dc601f5b3fefe084ce50fcbdc63e3385621bee0cbfa7b57f9ec3e67c2"
PACKAGE_EXTRA_CONFIGURE_ARGS="--enable-lib-only"
PACKAGE_DEPENDS="openssl"

builder_step_pre_configure() {
	rm -f CMakeLists.txt
}
