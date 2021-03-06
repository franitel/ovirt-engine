/*
 * Copyright oVirt Authors
 * SPDX-License-Identifier: Apache-2.0
*/

package org.ovirt.engine.api.v3.servers;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import org.ovirt.engine.api.resource.aaa.DomainsResource;
import org.ovirt.engine.api.v3.V3Server;
import org.ovirt.engine.api.v3.types.V3Domains;

@Produces({"application/xml", "application/json"})
public class V3DomainsServer extends V3Server<DomainsResource> {
    public V3DomainsServer(DomainsResource delegate) {
        super(delegate);
    }

    @GET
    public V3Domains list() {
        return adaptList(getDelegate()::list);
    }

    @Path("{id}")
    public V3DomainServer getDomainResource(@PathParam("id") String id) {
        return new V3DomainServer(getDelegate().getDomainResource(id));
    }
}
