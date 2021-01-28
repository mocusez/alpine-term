PACKAGE_VERSION="1.1.8"
PACKAGE_SRCURL="https://github.com/google/snappy/archive/${PACKAGE_VERSION}.tar.gz"
PACKAGE_SHA256="16b677f07832a612b0836178db7f374e414f94657c138e6993cbfc5dcc58651f"

builder_step_post_make_install() {
	mkdir -p "$PACKAGE_INSTALL_PREFIX/lib/pkgconfig"
	sed "s|@PACKAGE_PREFIX@|$PACKAGE_INSTALL_PREFIX|g" \
		"$PACKAGE_BUILDER_DIR/snappy.pc.in" \
		> "$PACKAGE_INSTALL_PREFIX/lib/pkgconfig/snappy.pc"
}
