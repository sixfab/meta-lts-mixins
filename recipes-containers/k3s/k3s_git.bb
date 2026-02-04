SUMMARY = "Production-Grade Container Scheduling and Management"
DESCRIPTION = "Lightweight Kubernetes, intended to be a fully compliant Kubernetes."
HOMEPAGE = "https://k3s.io/"
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://${S}/src/import/LICENSE;md5=2ee41112a44fe7014dce33e26468ba93"

GO_SRCURI_DESTSUFFIX = "${S}/src/import"

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI = "git://github.com/rancher/k3s.git;branch=release-1.31;name=k3s;protocol=https;destsuffix=${GO_SRCURI_DESTSUFFIX} \
           file://k3s.service \
           file://k3s-agent.service \
           file://k3s-agent \
           file://k3s-clean \
           file://cni-containerd-net.conf \
           file://0001-Finding-host-local-in-usr-libexec.patch;patchdir=src/import \
           file://k3s-killall.sh \
           file://modules.txt \
          "

SRC_URI[k3s.md5sum] = "363d3a08dc0b72ba6e6577964f6e94a5"
SRCREV_k3s = "452dbbc14c747a0070fdf007ef2239a6e5d8d934"

SRCREV_FORMAT = "k3s_fuse"
PV = "v1.31.1+k3s1+git${SRCREV_k3s}"

# Fetch airgap images for k3s
# This is needed for the k3s agent to run without internet access.
K3S_AIRGAP_IMAGES_NAME = "k3s-airgap-images-arm64.tar.zst"
K3S_AIRGAP_IMAGES_LINK = "https://github.com/k3s-io/k3s/releases/download/v1.31.1%2Bk3s1/${K3S_AIRGAP_IMAGES_NAME}"
CHECKSUM_LINK = "https://github.com/k3s-io/k3s/releases/download/v1.31.1%2Bk3s1/sha256sum-arm64.txt"


include ${THISDIR}/src_uri.inc

CNI_NETWORKING_FILES ?= "${WORKDIR}/cni-containerd-net.conf"

inherit go
inherit goarch
inherit systemd
inherit cni_networking

COMPATIBLE_HOST = "^(?!mips).*"

PACKAGECONFIG = ""
PACKAGECONFIG[upx] = ",,upx-native"
GO_IMPORT = "import"
GO_BUILD_LDFLAGS = "-X github.com/k3s-io/k3s/pkg/version.Version=${PV} \
                    -X github.com/k3s-io/k3s/pkg/version.GitCommit=${@d.getVar('SRCREV_k3s', d, 1)[:8]} \
                    -w -s \
                   "
BIN_PREFIX ?= "${exec_prefix}/local"

inherit features_check
REQUIRED_DISTRO_FEATURES ?= "seccomp"

DEPENDS += "rsync-native"

include ${THISDIR}/relocation.inc

do_compile() {
        export GOPATH="${S}/src/import/.gopath:${S}/src/import/vendor:${STAGING_DIR_TARGET}/${prefix}/local/go"
        export CGO_ENABLED="1"
        export GOFLAGS="-mod=vendor"

        # TAGS="static_build ctrd no_btrfs netcgo osusergo providerless"
	TAGS="static_build netcgo osusergo providerless"

        cd ${S}/src/import

	if ! [ -e vendor/.noclobber ]; then
            ln -sf vendor.copy vendor
	else
	    echo "[INFO]: no clobber on vendor"
	fi

        # these are bad symlinks, go validates them and breaks the build if they are present
        rm -f vendor/go.etcd.io/etcd/client/v*/example_*
        rm -f vendor/go.etcd.io/etcd/client/v*/concurrency/example_*.go

	# Note: if no_brtfs is used in the tags, we'll violate build
	#       constraints, and the following files need to have them
	#       removed for the build to continue:
	#
	#         vendor/github.com/containerd/containerd/snapshots/btrfs/plugin/*.go

        cp ${WORKDIR}/modules.txt vendor/

        VERSION_GOLANG="$(go version | cut -d" " -f3)"
        ${GO} build -trimpath -tags "$TAGS" -ldflags "-X github.com/k3s-io/k3s/pkg/version.UpstreamGolang=$VERSION_GOLANG  ${GO_BUILD_LDFLAGS} -w -s" -o ./dist/artifacts/k3s ./cmd/server/main.go

        # Use UPX if it is enabled (and thus exists) to compress binary
        if command -v upx > /dev/null 2>&1; then
                upx -9 ./dist/artifacts/k3s
        fi
}


# Fetch airgap images for k3s
# This is needed for the k3s agent to run without internet access.
do_fetch_airgap_images[network] = "1"
do_fetch_airgap_images() {
    wget -O "${WORKDIR}/k3s-airgap-images-checksum.txt" "${CHECKSUM_LINK}"
    # find the checksum for the airgap images
    AIRGAP_IMAGES_CHECKSUM=$(grep "${K3S_AIRGAP_IMAGES_NAME}$" "${WORKDIR}/k3s-airgap-images-checksum.txt" | awk '{print $1}')

    # Ensure the airgap images are fetched
    if [ ! -f "${WORKDIR}/${K3S_AIRGAP_IMAGES_NAME}" ]; then
        bbwarn "Airgap images not found, fetching..."
        wget -O "${WORKDIR}/${K3S_AIRGAP_IMAGES_NAME}" "${K3S_AIRGAP_IMAGES_LINK}"
    fi

    # Verify the checksum of the airgap images
    if [ -f "${WORKDIR}/${K3S_AIRGAP_IMAGES_NAME}" ]; then
        ACTUAL_CHECKSUM=$(sha256sum ${WORKDIR}/${K3S_AIRGAP_IMAGES_NAME} | awk '{print $1}')
        if [ "${ACTUAL_CHECKSUM}" != "${AIRGAP_IMAGES_CHECKSUM}" ]; then
            bbfatal "Checksum mismatch for ${K3S_AIRGAP_IMAGES_NAME} -> Expected: ${AIRGAP_IMAGES_CHECKSUM}, Actual: ${ACTUAL_CHECKSUM}"
        else
            bbnote "Checksum verified for ${K3S_AIRGAP_IMAGES_NAME}"
        fi
    else
        bbfatal "${K3S_AIRGAP_IMAGES_NAME} not found in ${WORKDIR}"
    fi
}

addtask do_fetch_airgap_images before do_install


do_install() {
        install -d "${D}${BIN_PREFIX}/bin"
        install -m 755 "${S}/src/import/dist/artifacts/k3s" "${D}${BIN_PREFIX}/bin"
        ln -sr "${D}/${BIN_PREFIX}/bin/k3s" "${D}${BIN_PREFIX}/bin/crictl"
        # We want to use the containerd provided ctr
        # ln -sr "${D}/${BIN_PREFIX}/bin/k3s" "${D}${BIN_PREFIX}/bin/ctr"
        ln -sr "${D}/${BIN_PREFIX}/bin/k3s" "${D}${BIN_PREFIX}/bin/kubectl"
        install -m 755 "${WORKDIR}/k3s-clean" "${D}${BIN_PREFIX}/bin"
        install -m 755 "${WORKDIR}/k3s-killall.sh" "${D}${BIN_PREFIX}/bin"

        if ${@bb.utils.contains('DISTRO_FEATURES','systemd','true','false',d)}; then
                install -D -m 0644 "${WORKDIR}/k3s.service" "${D}${systemd_system_unitdir}/k3s.service"
                install -D -m 0644 "${WORKDIR}/k3s-agent.service" "${D}${systemd_system_unitdir}/k3s-agent.service"
                sed -i "s#\(Exec\)\(.*\)=\(.*\)\(k3s\)#\1\2=${BIN_PREFIX}/bin/\4#g" "${D}${systemd_system_unitdir}/k3s.service" "${D}${systemd_system_unitdir}/k3s-agent.service"
                install -m 755 "${WORKDIR}/k3s-agent" "${D}${BIN_PREFIX}/bin"
        fi

	mkdir -p ${D}${datadir}/k3s/
	install -m 0755 ${S}/src/import/contrib/util/check-config.sh ${D}${datadir}/k3s/

        # Remove the old service file
        rm -f ${D}${systemd_system_unitdir}/k3s.service

        # Add the new service file
        install -d ${D}${systemd_system_unitdir}
        sed -i "s|@PERSISTENT_DATA_DIR@|${PERSISTENT_DATA_DIR}|g" ${WORKDIR}/k3s.service
        install -m 0644 ${WORKDIR}/k3s.service ${D}${systemd_system_unitdir}/k3s.service

        # Add airgap images to images folder
        install -d ${D}${K3S_DATA_DIR}/agent/images
        install -m 0644 ${WORKDIR}/${K3S_AIRGAP_IMAGES_NAME} ${D}${K3S_DATA_DIR}/agent/images/
        chown -R root:root ${D}${K3S_DATA_DIR}/agent/images
}

FILES:${PN} += "${K3S_DATA_DIR}/agent/images/${K3S_AIRGAP_IMAGES_NAME}"

PACKAGES =+ "${PN}-server ${PN}-agent"

SYSTEMD_PACKAGES = "${@bb.utils.contains('DISTRO_FEATURES','systemd','${PN}-server ${PN}-agent','',d)}"
SYSTEMD_SERVICE:${PN}-server = "${@bb.utils.contains('DISTRO_FEATURES','systemd','k3s.service','',d)}"
SYSTEMD_SERVICE:${PN}-agent = "${@bb.utils.contains('DISTRO_FEATURES','systemd','k3s-agent.service','',d)}"
SYSTEMD_AUTO_ENABLE:${PN}-agent = "disable"

FILES:${PN}-agent = "${BIN_PREFIX}/bin/k3s-agent"
FILES:${PN} += "${BIN_PREFIX}/bin/*"

RDEPENDS:${PN} = "k3s-cni conntrack-tools coreutils findutils iptables iproute2 ipset virtual-containerd"
RDEPENDS:${PN}-server = "${PN}"
RDEPENDS:${PN}-agent = "${PN}"

RRECOMMENDS:${PN} = "\
                     kernel-module-xt-addrtype \
                     kernel-module-xt-nat \
                     kernel-module-xt-multiport \
                     kernel-module-xt-conntrack \
                     kernel-module-xt-comment \
                     kernel-module-xt-mark \
                     kernel-module-xt-connmark \
                     kernel-module-vxlan \
                     kernel-module-xt-masquerade \
                     kernel-module-xt-statistic \
                     kernel-module-xt-physdev \
                     kernel-module-xt-nflog \
                     kernel-module-xt-limit \
                     kernel-module-nfnetlink-log \
                     kernel-module-ip-vs \
                     kernel-module-ip-vs-rr \
                     kernel-module-ip-vs-sh \
                     kernel-module-ip-vs-wrr \
                     "

RCONFLICTS:${PN} = "kubectl"

PACKAGES =+ "${PN}-contrib"
FILES:${PN}-contrib += "${datadir}/k3s/check-config.sh"
RDEPENDS:${PN}-contrib += "bash"

INHIBIT_PACKAGE_STRIP = "1"
INSANE_SKIP:${PN} += "ldflags already-stripped textrel"

FILES:${PN} += "${systemd_system_unitdir}/k3s.service"