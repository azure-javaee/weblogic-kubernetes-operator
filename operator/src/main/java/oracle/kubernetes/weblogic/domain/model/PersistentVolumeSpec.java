// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.model;

import java.util.List;
import java.util.Map;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1CSIPersistentVolumeSource;
import io.kubernetes.client.openapi.models.V1HostPathVolumeSource;
import io.kubernetes.client.openapi.models.V1VolumeNodeAffinity;
import io.swagger.annotations.ApiModelProperty;
import oracle.kubernetes.json.Description;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

public class PersistentVolumeSpec {

  @ApiModelProperty("accessModes contains all ways the volume can be mounted. "
      + "More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#access-modes")
  private List<String> accessModes = null;

  @ApiModelProperty("capacity is the description of the persistent volume's resources and capacity. "
      + "More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#capacity")
  private Map<String, Quantity> capacity = null;

  @Description("CSI represents storage that is handled by an external CSI driver (Beta feature)."
      + "\nRepresents storage that is managed by an external CSI volume driver (Beta feature)")
  private V1CSIPersistentVolumeSource csi;

  @Description("HostPath represents a directory on the host. Provisioned by a developer or tester."
      + " This is useful for single-node development and testing only! On-host storage is not supported in any way"
      + " and WILL NOT WORK in a multi-node cluster. More info:\n"
      + " https://kubernetes.io/docs/concepts/storage/volumes#hostpath\n"
      + "Represents a host path mapped into a pod. Host path volumes do not support ownership management"
      + " or SELinux relabeling.")
  private V1HostPathVolumeSource hostPath;

  @Description("NodeAffinity defines constraints that limit what nodes this volume can be"
      + " accessed from. This field influences the scheduling of pods that use this"
      + " volume.\n"
      + "VolumeNodeAffinity defines constraints that limit what nodes this volume\n"
      + " can be accessed from.")
  private V1VolumeNodeAffinity nodeAffinity;

  @ApiModelProperty("persistentVolumeReclaimPolicy defines what happens to a persistent volume when released from"
      + " its claim. Valid options are Retain (default for manually created PersistentVolumes),"
      + " Delete (default for dynamically provisioned PersistentVolumes), and Recycle (deprecated)."
      + " Recycle must be supported by the volume plugin underlying this PersistentVolume."
      + " More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes#reclaiming  ")
  private String persistentVolumeReclaimPolicy;

  @ApiModelProperty("storageClassName is the name of StorageClass to which this persistent volume belongs."
      + " Empty value means that this volume does not belong to any StorageClass.")
  private String storageClassName;

  @ApiModelProperty("volumeMode defines if a volume is intended to be used with a formatted filesystem "
      + "or to remain in raw block state. Value of Filesystem is implied when not included in spec.")
  private String volumeMode;

  @ApiModelProperty("mountOptions is the list of mount options, e.g. [\"ro\", \"soft\"]. Not validated - mount will"
      + " simply fail if one is invalid."
      + " More info: https://kubernetes.io/docs/concepts/storage/persistent-volumes/#mount-options")
  private List<String> mountOptions = null;

  @Override
  public String toString() {
    ToStringBuilder builder =
        new ToStringBuilder(this)
            .append("accessModes", accessModes)
            .append("capacity", capacity)
            .append("csi", csi)
            .append("hostPath", hostPath)
            .append("nodeAffinity", nodeAffinity)
            .append("persistentVolumeReclaimPolicy", persistentVolumeReclaimPolicy)
            .append("storageClassName", storageClassName)
            .append("volumeMode", volumeMode)
            .append("mountOptions", mountOptions);

    return builder.toString();
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder()
        .append(accessModes)
        .append(capacity)
        .append(csi)
        .append(hostPath)
        .append(nodeAffinity)
        .append(persistentVolumeReclaimPolicy)
        .append(storageClassName)
        .append(volumeMode)
        .append(mountOptions);

    return builder.toHashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    } else if (!(other instanceof InitDomain)) {
      return false;
    }

    PersistentVolumeSpec rhs = ((PersistentVolumeSpec) other);
    EqualsBuilder builder =
        new EqualsBuilder()
            .append(accessModes, rhs.accessModes)
            .append(capacity, rhs.capacity)
            .append(csi, rhs.csi)
            .append(hostPath, rhs.hostPath)
            .append(nodeAffinity, rhs.nodeAffinity)
            .append(persistentVolumeReclaimPolicy, rhs.persistentVolumeReclaimPolicy)
            .append(storageClassName, rhs.storageClassName)
            .append(volumeMode, rhs.volumeMode)
            .append(mountOptions, rhs.mountOptions);

    return builder.isEquals();
  }
}
