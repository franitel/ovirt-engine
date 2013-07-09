#
# ovirt-engine-setup -- ovirt engine setup
# Copyright (C) 2013 Red Hat, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


"""ovirt-host-setup core plugin."""


from otopi import util


from . import apache
from . import config
from . import core
from . import datadomain
from . import firewall_manager
from . import isodomain
from . import database
from . import ca


@util.export
def createPlugins(context):
    apache.Plugin(context=context)
    config.Plugin(context=context)
    core.Plugin(context=context)
    datadomain.Plugin(context=context)
    firewall_manager.Plugin(context=context)
    isodomain.Plugin(context=context)
    database.Plugin(context=context)
    ca.Plugin(context=context)


# vim: expandtab tabstop=4 shiftwidth=4
