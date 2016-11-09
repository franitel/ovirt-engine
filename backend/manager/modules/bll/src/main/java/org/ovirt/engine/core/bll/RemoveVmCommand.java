package org.ovirt.engine.core.bll;

import static org.ovirt.engine.core.bll.storage.disk.image.DisksFilter.ONLY_ACTIVE;
import static org.ovirt.engine.core.bll.storage.disk.image.DisksFilter.ONLY_NOT_SHAREABLE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.enterprise.event.Event;
import javax.inject.Inject;

import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.memory.MemoryUtils;
import org.ovirt.engine.core.bll.network.ExternalNetworkManager;
import org.ovirt.engine.core.bll.quota.QuotaConsumptionParameter;
import org.ovirt.engine.core.bll.quota.QuotaStorageConsumptionParameter;
import org.ovirt.engine.core.bll.quota.QuotaStorageDependent;
import org.ovirt.engine.core.bll.snapshots.SnapshotsValidator;
import org.ovirt.engine.core.bll.storage.disk.image.DisksFilter;
import org.ovirt.engine.core.bll.storage.disk.image.ImagesHandler;
import org.ovirt.engine.core.bll.tasks.CommandCoordinatorUtil;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandCallback;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.bll.validator.VmValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskImagesValidator;
import org.ovirt.engine.core.bll.validator.storage.MultipleStorageDomainsValidator;
import org.ovirt.engine.core.bll.validator.storage.StoragePoolValidator;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.LockProperties;
import org.ovirt.engine.core.common.action.LockProperties.Scope;
import org.ovirt.engine.core.common.action.RemoveAllVmCinderDisksParameters;
import org.ovirt.engine.core.common.action.RemoveAllVmImagesParameters;
import org.ovirt.engine.core.common.action.RemoveMemoryVolumesParameters;
import org.ovirt.engine.core.common.action.RemoveVmParameters;
import org.ovirt.engine.core.common.action.VdcActionParametersBase.EndProcedure;
import org.ovirt.engine.core.common.action.VdcActionType;
import org.ovirt.engine.core.common.action.VdcReturnValueBase;
import org.ovirt.engine.core.common.asynctasks.AsyncTaskCreationInfo;
import org.ovirt.engine.core.common.asynctasks.EntityInfo;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.businessentities.network.VmNic;
import org.ovirt.engine.core.common.businessentities.storage.CinderDisk;
import org.ovirt.engine.core.common.businessentities.storage.Disk;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.businessentities.storage.ImageStatus;
import org.ovirt.engine.core.common.businessentities.storage.LunDisk;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.locks.LockingGroup;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.utils.transaction.TransactionSupport;

@DisableInPrepareMode
@NonTransactiveCommandAttribute(forceCompensation = true)
public class RemoveVmCommand<T extends RemoveVmParameters> extends VmCommand<T> implements QuotaStorageDependent {

    @Inject
    private Event<Guid> vmDeleted;

    private List<CinderDisk> cinderDisks;

    /**
     * Constructor for command creation when compensation is applied on startup
     */
    protected RemoveVmCommand(Guid commandId) {
        super(commandId);
    }

    public RemoveVmCommand(T parameters, CommandContext commandContext) {
        super(parameters, commandContext);
    }

    @Override
    protected void init() {
        getParameters().setEntityInfo(new EntityInfo(VdcObjectType.VM, getVmId()));
        if (getVm() != null) {
            setStoragePoolId(getVm().getStoragePoolId());
        }
    }

    @Override
    protected LockProperties applyLockProperties(LockProperties lockProperties) {
        return lockProperties.withScope(Scope.Execution);
    }

    @Override
    protected void executeVmCommand() {
        if (getVm().getStatus() != VMStatus.ImageLocked) {
            vmHandler.lockVm(getVm().getDynamicData(), getCompensationContext());
        }
        freeLock();
        setSucceeded(removeVm());
    }

    private boolean removeVm() {
        final List<DiskImage> diskImages = DisksFilter.filterImageDisks(getVm().getDiskList(), ONLY_NOT_SHAREABLE,
                ONLY_ACTIVE);

        final List<LunDisk> lunDisks =
                ImagesHandler.filterDiskBasedOnLuns(getVm().getDiskMap().values(), false);

        for (VmNic nic : getInterfaces()) {
            new ExternalNetworkManager(nic).deallocateIfExternal();
        }

        removeMemoryVolumes();

        TransactionSupport.executeInNewTransaction(() -> {
            removeVmFromDb();
            if (getParameters().isRemoveDisks()) {
                for (DiskImage image : diskImages) {
                    getCompensationContext().snapshotEntityStatus(image.getImage(), ImageStatus.ILLEGAL);
                    ImagesHandler.updateImageStatus(image.getImage().getId(), ImageStatus.LOCKED);
                }

                for (LunDisk lunDisk : lunDisks) {
                    ImagesHandler.removeLunDisk(lunDisk);
                }

                getCompensationContext().stateChanged();
            }
            else {
                for (DiskImage image : diskImages) {
                    imageDao.updateImageVmSnapshotId(image.getImageId(), null);
                }
            }
            return null;
        });

        Collection<DiskImage> unremovedDisks = Collections.emptyList();
        if (getParameters().isRemoveDisks()) {
            if (!diskImages.isEmpty()) {
                unremovedDisks = removeVmImages(diskImages).getActionReturnValue();
            }
            unremovedDisks.addAll(removeCinderDisks());
            if (!unremovedDisks.isEmpty()) {
                processUnremovedDisks(unremovedDisks);
                return false;
            }
        }

        vmDeleted.fire(getVmId());
        return true;
    }

    private void removeMemoryVolumes() {
        Set<String> memoryStates = MemoryUtils.getMemoryVolumesFromSnapshots(snapshotDao.getAll(getVmId()));
        for (String memoryState : memoryStates) {
            VdcReturnValueBase retVal = runInternalAction(
                    VdcActionType.RemoveMemoryVolumes,
                    buildRemoveMemoryVolumesParameters(memoryState, getVmId()),
                    cloneContextAndDetachFromParent());

            if (!retVal.getSucceeded()) {
                log.error("Failed to remove memory volumes while removing vm '{}' (volumes: '{}')",
                        getVmId(), memoryState);
            }
        }
    }

    private RemoveMemoryVolumesParameters buildRemoveMemoryVolumesParameters(String memoryState, Guid vmId) {
        RemoveMemoryVolumesParameters params = new RemoveMemoryVolumesParameters(memoryState, vmId, true);
        params.setEntityInfo(getParameters().getEntityInfo());
        return params;
    }

    @Override
    protected boolean validate() {
        if (getVm() == null) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_VM_NOT_FOUND);
        }

        if (!canRunActionOnNonManagedVm()) {
            return false;
        }

        if (getVm().isDeleteProtected()) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_DELETE_PROTECTION_ENABLED);
        }

        vmHandler.updateDisksFromDb(getVm());
        getParameters().setUseCinderCommandCallback(getParameters().isRemoveDisks() && !getCinderDisks().isEmpty());

        if (!getParameters().isRemoveDisks() && !canRemoveVmWithDetachDisks()) {
            return false;
        }

        switch (getVm().getStatus()) {
            case Unassigned:
            case Down:
            case ImageIllegal:
            case ImageLocked:
                break;
            case Suspended:
                return failValidation(EngineMessage.VM_CANNOT_REMOVE_VM_WHEN_STATUS_IS_NOT_DOWN);
            default:
                return (getVm().isHostedEngine() && isInternalExecution()) || failValidation(EngineMessage.ACTION_TYPE_FAILED_VM_IS_RUNNING);
        }

        if (getVm().getVmPoolId() != null) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_VM_ATTACHED_TO_POOL);
        }

        // enable to remove vms without images
        SnapshotsValidator snapshotsValidator = new SnapshotsValidator();
        if (!validate(snapshotsValidator.vmNotDuringSnapshot(getVmId()))) {
            return false;
        }

        if (!getVm().getDiskMap().isEmpty() && !validate(new StoragePoolValidator(getStoragePool()).isUp())) {
            return false;
        }

        Collection<Disk> vmDisks = getVm().getDiskMap().values();
        List<DiskImage> vmImages = DisksFilter.filterImageDisks(vmDisks, ONLY_NOT_SHAREABLE, ONLY_ACTIVE);
        vmImages.addAll(ImagesHandler.filterDisksBasedOnCinder(vmDisks));
        if (!vmImages.isEmpty()) {
            Set<Guid> storageIds = ImagesHandler.getAllStorageIdsForImageIds(vmImages);
            MultipleStorageDomainsValidator storageValidator = new MultipleStorageDomainsValidator(getVm().getStoragePoolId(), storageIds);
            if (!validate(storageValidator.allDomainsExistAndActive())) {
                return false;
            }

            DiskImagesValidator diskImagesValidator = new DiskImagesValidator(vmImages);
            if (!getParameters().getForce() && !validate(diskImagesValidator.diskImagesNotLocked())) {
                return false;
            }
        }

        // Handle VM status with ImageLocked
        VmValidator vmValidator = new VmValidator(getVm());
        ValidationResult vmLockedValidatorResult = vmValidator.vmNotLocked();
        if (!vmLockedValidatorResult.isValid()) {
            // without force remove, we can't remove the VM
            if (!getParameters().getForce()) {
                return failValidation(vmLockedValidatorResult.getMessages());
            }

            // If it is force, we cannot remove if there are task
            if (CommandCoordinatorUtil.hasTasksByStoragePoolId(getVm().getStoragePoolId())) {
                return failValidation(EngineMessage.VM_CANNOT_REMOVE_HAS_RUNNING_TASKS);
            }
        }

        if (getParameters().isRemoveDisks() && !validate(vmValidator.vmNotHavingDeviceSnapshotsAttachedToOtherVms(false))) {
            return false;
        }

        return true;
    }

    @Override
    protected void setActionMessageParameters() {
        addValidationMessage(EngineMessage.VAR__ACTION__REMOVE);
        addValidationMessage(EngineMessage.VAR__TYPE__VM);
    }

    private boolean canRemoveVmWithDetachDisks() {
        if (!Guid.Empty.equals(getVm().getVmtGuid())) {
            return failValidation(EngineMessage.VM_CANNOT_REMOVE_WITH_DETACH_DISKS_BASED_ON_TEMPLATE);
        }

        for (Disk disk : getVm().getDiskList()) {
            List<DiskImage> diskImageList = diskImageDao.getAllSnapshotsForImageGroup(disk.getId());
            if (diskImageList.size() > 1) {
                return failValidation(EngineMessage.VM_CANNOT_REMOVE_WITH_DETACH_DISKS_SNAPSHOTS_EXIST);
            }
        }

        return true;
    }

    protected VdcReturnValueBase removeVmImages(List<DiskImage> images) {
        VdcReturnValueBase vdcRetValue =
                runInternalActionWithTasksContext(VdcActionType.RemoveAllVmImages,
                        buildRemoveAllVmImagesParameters(images));

        if (vdcRetValue.getSucceeded()) {
            getTaskIdList().addAll(vdcRetValue.getInternalVdsmTaskIdList());
        }

        return vdcRetValue;
    }

    private RemoveAllVmImagesParameters buildRemoveAllVmImagesParameters(List<DiskImage> images) {
        RemoveAllVmImagesParameters params = new RemoveAllVmImagesParameters(getVmId(), images);
        if (!isExecutedAsChildCommand()) {
            params.setParentCommand(getActionType());
            params.setEntityInfo(getParameters().getEntityInfo());
            params.setParentParameters(getParameters());
        } else {
            params.setParentCommand(getParameters().getParentCommand());
            params.setEntityInfo(getParameters().getParentParameters().getEntityInfo());
            params.setParentParameters(getParameters().getParentParameters());
        }

        return params;
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        return getSucceeded() ? AuditLogType.USER_REMOVE_VM_FINISHED : AuditLogType.USER_REMOVE_VM_FINISHED_WITH_ILLEGAL_DISKS;
    }

    @Override
    protected Map<String, Pair<String, String>> getExclusiveLocks() {
        return Collections.singletonMap(getVmId().toString(),
                LockMessagesMatchUtil.makeLockingPair(LockingGroup.VM, EngineMessage.ACTION_TYPE_FAILED_OBJECT_LOCKED));
    }

    protected void removeVmFromDb() {
        removeVmUsers();
        removeVmNetwork();
        removeVmSnapshots();
        removeVmStatic(getParameters().isRemovePermissions());
        removeIcons();
    }

    /**
     * It does just best effort service. There is also global icon cleanup during startup {@link Backend#iconCleanup()}
     */
    private void removeIcons() {
        if (getVm() != null) {
            vmIconDao.removeIfUnused(getVm().getStaticData().getLargeIconId());
            vmIconDao.removeIfUnused(getVm().getStaticData().getSmallIconId());
        }
    }

    /**
     * The following method will perform a removing of all cinder disks from vm. These is only DB operation
     */
    private Collection<CinderDisk> removeCinderDisks() {
        Collection<CinderDisk> failedRemoveCinderDisks = null;
        if (getParameters().isRemoveDisks()) {
            List<CinderDisk> cinderDisks = getCinderDisks();
            if (cinderDisks.isEmpty()) {
                return Collections.emptyList();
            }
            RemoveAllVmCinderDisksParameters param = new RemoveAllVmCinderDisksParameters(getVmId(), cinderDisks);
            param.setEndProcedure(EndProcedure.COMMAND_MANAGED);
            Future<VdcReturnValueBase> future = CommandCoordinatorUtil.executeAsyncCommand(
                    VdcActionType.RemoveAllVmCinderDisks,
                    withRootCommandInfo(param),
                    cloneContextAndDetachFromParent());
            try {
                failedRemoveCinderDisks = future.get().getActionReturnValue();
            } catch (InterruptedException | ExecutionException e) {
                failedRemoveCinderDisks = cinderDisks;
                log.error("Exception", e);
            }
        }
        return failedRemoveCinderDisks;
    }

    private List<CinderDisk> getCinderDisks() {
        if (cinderDisks == null) {
            cinderDisks = ImagesHandler.filterDisksBasedOnCinder(getVm().getDiskMap().values());
        }
        return cinderDisks;
    }

    @Override
    protected void endVmCommand() {
        // no audit log print here as the vm was already removed during the execute phase.
        setCommandShouldBeLogged(false);

        setSucceeded(true);
    }

    private void processUnremovedDisks(Collection<? extends DiskImage> diskImages) {
        addCustomValue("DisksNames", diskImages.stream().map(DiskImage::getDiskAlias).collect(Collectors.joining(",")));
    }

    @Override
    public void addQuotaPermissionSubject(List<PermissionSubject> quotaPermissionList) {
        //
    }

    @Override
    public List<QuotaConsumptionParameter> getQuotaStorageConsumptionParameters() {
        if (getParameters().isRemoveDisks()) {
            List<QuotaConsumptionParameter> list = new ArrayList<>();
            ImagesHandler.fillImagesBySnapshots(getVm());
            for (DiskImage disk : getVm().getDiskList()) {
                for (DiskImage snapshot : disk.getSnapshots()) {
                    if (snapshot.getQuotaId() != null && !Guid.Empty.equals(snapshot.getQuotaId())) {
                        if (snapshot.getActive()) {
                            list.add(new QuotaStorageConsumptionParameter(
                                    snapshot.getQuotaId(),
                                    null,
                                    QuotaStorageConsumptionParameter.QuotaAction.RELEASE,
                                    disk.getStorageIds().get(0),
                                    (double) snapshot.getSizeInGigabytes()));
                        } else {
                            list.add(new QuotaStorageConsumptionParameter(
                                    snapshot.getQuotaId(),
                                    null,
                                    QuotaStorageConsumptionParameter.QuotaAction.RELEASE,
                                    disk.getStorageIds().get(0),
                                    snapshot.getActualSize()));
                        }
                    }
                }
            }
            return list;
        }
        return Collections.emptyList();
    }

    @Override
    public Guid createTask(Guid taskId,
            AsyncTaskCreationInfo asyncTaskCreationInfo,
            VdcActionType parentCommand,
            VdcObjectType entityType,
            Guid... entityIds) {
        return super.createTaskInCurrentTransaction(taskId, asyncTaskCreationInfo, parentCommand, entityType, entityIds);
    }

    @Override
    public CommandCallback getCallback() {
        return getParameters().isUseCinderCommandCallback() ? new ConcurrentChildCommandsExecutionCallback() : null;
    }
}
