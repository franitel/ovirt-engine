/*
 * Copyright oVirt Authors
 * SPDX-License-Identifier: Apache-2.0
*/

package org.ovirt.engine.api.v3.helpers;

import java.util.List;

import javax.ws.rs.core.Response;

import org.ovirt.engine.api.v3.types.V3Actions;
import org.ovirt.engine.api.v3.types.V3Disk;
import org.ovirt.engine.api.v3.types.V3Link;
import org.ovirt.engine.api.v3.types.V3Template;

public class V3TemplateHelper {
    public static void addDisksLink(Response response) {
        Object entity = response.getEntity();
        if (entity instanceof V3Template) {
            addDisksLink((V3Template) entity);
        }
    }

    public static void addDisksLink(V3Template template) {
        if (template != null) {
            V3LinkHelper.addLink(template.getLinks(), "disks", "templates", template.getId(), "disks");
        }
    }

    /**
     * Version 4 of the API can't reliably build the links for the "disks" collection because it has been removed,
     * so we need to remove all the links and re-add them explicitly.
     */
    public static void fixDiskLinks(String templateId, V3Disk disk) {
        // Fix the link of the disk itself:
        disk.setHref(V3LinkHelper.linkHref("templates", templateId, "disks", disk.getId()));

        // Remove all the action links and add them again:
        V3Actions actions = disk.getActions();
        if (actions != null) {
            List<V3Link> links = actions.getLinks();
            links.clear();
            V3LinkHelper.addLink(links, "copy", "templates", templateId, "disks", disk.getId(), "copy");
            V3LinkHelper.addLink(links, "export", "templates", templateId, "disks", disk.getId(), "export");
        }
    }
}
