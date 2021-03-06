/*
 * Copyright oVirt Authors
 * SPDX-License-Identifier: Apache-2.0
*/

package org.ovirt.engine.api.v3.adapters;

import static org.ovirt.engine.api.v3.adapters.V3InAdapters.adaptIn;

import java.util.List;

import org.ovirt.engine.api.model.Network;
import org.ovirt.engine.api.model.NetworkLabels;
import org.ovirt.engine.api.model.NetworkStatus;
import org.ovirt.engine.api.model.NetworkUsage;
import org.ovirt.engine.api.v3.V3Adapter;
import org.ovirt.engine.api.v3.types.V3Network;

public class V3NetworkInAdapter implements V3Adapter<V3Network, Network> {
    @Override
    public Network adapt(V3Network from) {
        Network to = new Network();
        if (from.isSetLinks()) {
            to.getLinks().addAll(adaptIn(from.getLinks()));
        }
        if (from.isSetActions()) {
            to.setActions(adaptIn(from.getActions()));
        }
        if (from.isSetCluster()) {
            to.setCluster(adaptIn(from.getCluster()));
        }
        if (from.isSetComment()) {
            to.setComment(from.getComment());
        }
        if (from.isSetDataCenter()) {
            to.setDataCenter(adaptIn(from.getDataCenter()));
        }
        if (from.isSetDescription()) {
            to.setDescription(from.getDescription());
        }
        if (from.isSetDisplay()) {
            to.setDisplay(from.isDisplay());
        }
        if (from.isSetId()) {
            to.setId(from.getId());
        }
        if (from.isSetHref()) {
            to.setHref(from.getHref());
        }
        if (from.isSetIp()) {
            to.setIp(adaptIn(from.getIp()));
        }
        if (from.isSetLabels()) {
            to.setNetworkLabels(new NetworkLabels());
            to.getNetworkLabels().getNetworkLabels().addAll(adaptIn(from.getLabels().getLabels()));
        }
        if (from.isSetMtu()) {
            to.setMtu(from.getMtu());
        }
        if (from.isSetName()) {
            to.setName(from.getName());
        }
        if (from.isSetProfileRequired()) {
            to.setProfileRequired(from.isProfileRequired());
        }
        if (from.isSetQos()) {
            to.setQos(adaptIn(from.getQos()));
        }
        if (from.isSetRequired()) {
            to.setRequired(from.isRequired());
        }
        if (from.isSetStatus() && from.getStatus().isSetState()) {
            to.setStatus(NetworkStatus.fromValue(from.getStatus().getState()));
        }
        if (from.isSetStp()) {
            to.setStp(from.isStp());
        }
        if (from.isSetUsages()) {
            Network.UsagesList toUsages = to.getUsages();
            if (toUsages == null) {
                toUsages = new Network.UsagesList();
                to.setUsages(toUsages);
            }
            List<NetworkUsage> toList = toUsages.getUsages();
            from.getUsages().getUsages().forEach(usage -> toList.add(NetworkUsage.fromValue(usage)));
        }
        if (from.isSetVlan()) {
            to.setVlan(adaptIn(from.getVlan()));
        }
        return to;
    }
}
