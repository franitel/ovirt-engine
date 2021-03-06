/*
 * Copyright oVirt Authors
 * SPDX-License-Identifier: Apache-2.0
*/

package org.ovirt.engine.api.v3.adapters;

import static org.ovirt.engine.api.v3.adapters.V3OutAdapters.adaptOut;

import org.ovirt.engine.api.model.CpuProfile;
import org.ovirt.engine.api.v3.V3Adapter;
import org.ovirt.engine.api.v3.types.V3CpuProfile;

public class V3CpuProfileOutAdapter implements V3Adapter<CpuProfile, V3CpuProfile> {
    @Override
    public V3CpuProfile adapt(CpuProfile from) {
        V3CpuProfile to = new V3CpuProfile();
        if (from.isSetLinks()) {
            to.getLinks().addAll(adaptOut(from.getLinks()));
        }
        if (from.isSetActions()) {
            to.setActions(adaptOut(from.getActions()));
        }
        if (from.isSetCluster()) {
            to.setCluster(adaptOut(from.getCluster()));
        }
        if (from.isSetComment()) {
            to.setComment(from.getComment());
        }
        if (from.isSetDescription()) {
            to.setDescription(from.getDescription());
        }
        if (from.isSetId()) {
            to.setId(from.getId());
        }
        if (from.isSetHref()) {
            to.setHref(from.getHref());
        }
        if (from.isSetName()) {
            to.setName(from.getName());
        }
        if (from.isSetQos()) {
            to.setQos(adaptOut(from.getQos()));
        }
        return to;
    }
}
