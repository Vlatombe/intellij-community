// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.ide.impl.legacyBridge.module

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.RootProvider
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.util.EventDispatcher
import com.intellij.util.containers.BidirectionalMultiMap
import com.intellij.util.containers.MultiMap
import com.intellij.workspaceModel.ide.WorkspaceModel
import com.intellij.workspaceModel.ide.WorkspaceModelChangeListener
import com.intellij.workspaceModel.ide.WorkspaceModelTopics
import com.intellij.workspaceModel.ide.impl.legacyBridge.library.LibraryNameGenerator
import com.intellij.workspaceModel.ide.legacyBridge.ModuleDependencyListener
import com.intellij.workspaceModel.ide.legacyBridge.ModuleDependencyIndex
import com.intellij.workspaceModel.storage.VersionedStorageChange
import com.intellij.workspaceModel.storage.bridgeEntities.api.*
import java.util.function.Supplier

class ModuleDependencyIndexImpl(private val project: Project): ModuleDependencyIndex, Disposable {
  companion object {
    private const val LIBRARY_NAME_DELIMITER = ":"
    @JvmStatic
    private val LOG = logger<ModuleDependencyIndexImpl>()
  }

  private val eventDispatcher = EventDispatcher.create(ModuleDependencyListener::class.java)
  
  private val libraryTablesListener = LibraryTablesListener()
  private val jdkChangeListener = JdkChangeListener()
  
  init {
    if (!project.isDefault) {
      val bus = project.messageBus.connect(this)

      WorkspaceModelTopics.getInstance(project).subscribeAfterModuleLoading(bus, object : WorkspaceModelChangeListener {
        override fun changed(event: VersionedStorageChange) {
          if (project.isDisposed) return

          // Roots changed event should be fired for the global libraries linked with module
          val moduleChanges = event.getChanges(ModuleEntity::class.java)
          for (change in moduleChanges) {
            change.oldEntity?.let { removeTrackedLibrariesAndJdkFromEntity(it) }
            change.newEntity?.let { addTrackedLibraryAndJdkFromEntity(it) }
          }
        }
      })

      bus.subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, jdkChangeListener)
    }
  }

  override fun addListener(listener: ModuleDependencyListener) {
    eventDispatcher.addListener(listener)
  }

  override fun setupTrackedLibrariesAndJdks() {
    val currentStorage = WorkspaceModel.getInstance(project).entityStorage.current
    for (moduleEntity in currentStorage.entities(ModuleEntity::class.java)) {
      addTrackedLibraryAndJdkFromEntity(moduleEntity)
    }
  }

  override fun hasProjectSdkDependency(): Boolean {
    return jdkChangeListener.hasProjectSdkDependency()
  }

  override fun hasDependencyOn(libraryId: LibraryId): Boolean {
    return libraryId.tableId is LibraryTableId.ModuleLibraryTableId || libraryTablesListener.hasDependencyOn(libraryId)
  }

  private fun addTrackedLibraryAndJdkFromEntity(moduleEntity: ModuleEntity) {
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    LOG.debug { "Add tracked global libraries and JDK from ${moduleEntity.name}" }
    val libraryTablesRegistrar = LibraryTablesRegistrar.getInstance()
    moduleEntity.dependencies.forEach {
      when {
        it is ModuleDependencyItem.Exportable.LibraryDependency && it.library.tableId !is LibraryTableId.ModuleLibraryTableId -> {
          val libraryName = it.library.name
          val libraryLevel = it.library.tableId.level
          val libraryTable = libraryTablesRegistrar.getLibraryTableByLevel(libraryLevel, project) ?: return@forEach
          if (libraryTablesListener.isEmpty(libraryLevel)) libraryTable.addListener(libraryTablesListener)
          libraryTablesListener.addTrackedLibrary(moduleEntity, libraryTable, libraryName)
        }
        it is ModuleDependencyItem.SdkDependency || it is ModuleDependencyItem.InheritedSdkDependency -> {
          jdkChangeListener.addTrackedJdk(it, moduleEntity)
        }
      }
    }
  }

  private fun removeTrackedLibrariesAndJdkFromEntity(moduleEntity: ModuleEntity) {
    LOG.debug { "Removed tracked global libraries and JDK from ${moduleEntity.name}" }
    val libraryTablesRegistrar = LibraryTablesRegistrar.getInstance()
    moduleEntity.dependencies.forEach {
      when {
        it is ModuleDependencyItem.Exportable.LibraryDependency && it.library.tableId is LibraryTableId.GlobalLibraryTableId -> {
          val libraryName = it.library.name
          val libraryLevel = it.library.tableId.level
          val libraryTable = libraryTablesRegistrar.getLibraryTableByLevel(libraryLevel, project) ?: return@forEach
          libraryTablesListener.unTrackLibrary(moduleEntity, libraryTable, libraryName)
          if (libraryTablesListener.isEmpty(libraryLevel)) libraryTable.removeListener(libraryTablesListener)
        }
        it is ModuleDependencyItem.SdkDependency || it is ModuleDependencyItem.InheritedSdkDependency -> {
          jdkChangeListener.removeTrackedJdk(it, moduleEntity)
        }
      }
    }
  }

  override fun dispose() {
    if (project.isDefault) return

    val libraryTablesRegistrar = LibraryTablesRegistrar.getInstance()
    libraryTablesListener.getLibraryLevels().forEach { libraryLevel ->
      val libraryTable = libraryTablesRegistrar.getLibraryTableByLevel(libraryLevel, project)
      libraryTable?.libraryIterator?.forEach { 
        eventDispatcher.multicaster.removedDependencyOn(it)
      }
      libraryTable?.removeListener(libraryTablesListener)
    }
    libraryTablesListener.clear()
    jdkChangeListener.unsubscribeListeners()
  }

  // Listener for global libraries linked to module
  private inner class LibraryTablesListener : LibraryTable.Listener {
    private val librariesPerModuleMap = BidirectionalMultiMap<ModuleId, String>()

    fun addTrackedLibrary(moduleEntity: ModuleEntity, libraryTable: LibraryTable, libraryName: String) {
      val library = libraryTable.getLibraryByName(libraryName)
      val libraryIdentifier = getLibraryIdentifier(libraryTable, libraryName)
      if (!librariesPerModuleMap.containsValue(libraryIdentifier) && library != null) {
        eventDispatcher.multicaster.addedDependencyOn(library)
      }
      librariesPerModuleMap.put(moduleEntity.persistentId, libraryIdentifier)
    }

    fun unTrackLibrary(moduleEntity: ModuleEntity, libraryTable: LibraryTable, libraryName: String) {
      val library = libraryTable.getLibraryByName(libraryName)
      val libraryIdentifier = getLibraryIdentifier(libraryTable, libraryName)
      librariesPerModuleMap.remove(moduleEntity.persistentId, libraryIdentifier)
      if (!librariesPerModuleMap.containsValue(libraryIdentifier) && library != null) {
        eventDispatcher.multicaster.removedDependencyOn(library)
      }
    }

    fun isEmpty(libraryLevel: String) = librariesPerModuleMap.values.none { it.startsWith("$libraryLevel$LIBRARY_NAME_DELIMITER") }

    fun getLibraryLevels() = librariesPerModuleMap.values.mapTo(HashSet()) { it.substringBefore(LIBRARY_NAME_DELIMITER) }

    override fun afterLibraryAdded(newLibrary: Library) {
      if (hasDependencyOn(newLibrary)) {
        eventDispatcher.multicaster.referencedLibraryAdded(newLibrary)
      }
    }

    override fun afterLibraryRemoved(library: Library) {
      if (hasDependencyOn(library)) {
        eventDispatcher.multicaster.referencedLibraryRemoved(library)
      }
    }

    private fun hasDependencyOn(library: Library) = librariesPerModuleMap.containsValue(getLibraryIdentifier(library))
    fun hasDependencyOn(libraryId: LibraryId) = librariesPerModuleMap.containsValue(getLibraryIdentifier(libraryId))

    override fun afterLibraryRenamed(library: Library, oldName: String?) {
      val libraryTable = library.table
      val newName = library.name
      if (libraryTable != null && oldName != null && newName != null) {
        val affectedModules = librariesPerModuleMap.getKeys(getLibraryIdentifier(libraryTable, oldName))
        if (affectedModules.isNotEmpty()) {
          val libraryTableId = LibraryNameGenerator.getLibraryTableId(libraryTable.tableLevel)
          WorkspaceModel.getInstance(project).updateProjectModel { builder ->
            //maybe it makes sense to simplify this code by reusing code from PEntityStorageBuilder.updateSoftReferences
            affectedModules.mapNotNull { builder.resolve(it) }.forEach { module ->
              val updated = module.dependencies.map {
                when {
                  it is ModuleDependencyItem.Exportable.LibraryDependency && it.library.tableId == libraryTableId && it.library.name == oldName ->
                    it.copy(library = LibraryId(newName, libraryTableId))
                  else -> it
                }
              } as MutableList<ModuleDependencyItem>
              builder.modifyEntity(module) {
                dependencies = updated
              }
            }
          }
        }
      }
    }

    private fun getLibraryIdentifier(library: Library) = "${library.table.tableLevel}$LIBRARY_NAME_DELIMITER${library.name}"
    private fun getLibraryIdentifier(libraryId: LibraryId) = "${libraryId.tableId.level}$LIBRARY_NAME_DELIMITER${libraryId.name}"
    private fun getLibraryIdentifier(libraryTable: LibraryTable,
                                     libraryName: String) = "${libraryTable.tableLevel}$LIBRARY_NAME_DELIMITER$libraryName"

    fun clear() = librariesPerModuleMap.clear()
  }

  private inner class JdkChangeListener : ProjectJdkTable.Listener {
    private val sdkDependencies = MultiMap.createSet<ModuleDependencyItem, ModuleId>()
    private val watchedSdks = HashSet<RootProvider>()

    override fun jdkAdded(jdk: Sdk) {
      if (hasDependencies(jdk)) {
        if (watchedSdks.add(jdk.rootProvider)) {
          eventDispatcher.multicaster.addedDependencyOn(jdk)
        }
        eventDispatcher.multicaster.referencedSdkAdded(jdk)
      }
    }

    override fun jdkNameChanged(jdk: Sdk, previousName: String) {
      val sdkDependency = ModuleDependencyItem.SdkDependency(previousName, jdk.sdkType.name)
      val affectedModules = sdkDependencies.get(sdkDependency)
      if (affectedModules.isNotEmpty()) {
        WorkspaceModel.getInstance(project).updateProjectModel { builder ->
          for (moduleId in affectedModules) {
            val module = moduleId.resolve(builder) ?: continue
            val updated = module.dependencies.map {
              when (it) {
                is ModuleDependencyItem.SdkDependency -> ModuleDependencyItem.SdkDependency(jdk.name, jdk.sdkType.name)
                else -> it
              }
            } as MutableList<ModuleDependencyItem>
            builder.modifyEntity(module) {
              dependencies = updated
            }
          }
        }
      }
    }

    override fun jdkRemoved(jdk: Sdk) {
      if (watchedSdks.remove(jdk.rootProvider)) {
        eventDispatcher.multicaster.removedDependencyOn(jdk)
      }
      if (hasDependencies(jdk)) {
        eventDispatcher.multicaster.referencedSdkRemoved(jdk)
      }
    }

    fun addTrackedJdk(sdkDependency: ModuleDependencyItem, moduleEntity: ModuleEntity) {
      val sdk = findSdk(sdkDependency)
      if (sdk != null && watchedSdks.add(sdk.rootProvider)) {
        eventDispatcher.multicaster.addedDependencyOn(sdk)
      }
      sdkDependencies.putValue(sdkDependency, moduleEntity.persistentId)
    }

    fun removeTrackedJdk(sdkDependency: ModuleDependencyItem, moduleEntity: ModuleEntity) {
      sdkDependencies.remove(sdkDependency, moduleEntity.persistentId)
      val sdk = findSdk(sdkDependency)
      if (sdk != null && !hasDependencies(sdk) && watchedSdks.remove(sdk.rootProvider)) {
        eventDispatcher.multicaster.removedDependencyOn(sdk)
      }
    }

    fun hasProjectSdkDependency(): Boolean {
      return sdkDependencies.get(ModuleDependencyItem.InheritedSdkDependency).isNotEmpty()
    }

    private val projectRootManager by lazy { ProjectRootManager.getInstance(project) }

    private fun findSdk(sdkDependency: ModuleDependencyItem): Sdk? = when (sdkDependency) {
      is ModuleDependencyItem.InheritedSdkDependency -> projectRootManager.projectSdk
      is ModuleDependencyItem.SdkDependency -> ProjectJdkTable.getInstance().findJdk(sdkDependency.sdkName, sdkDependency.sdkType)
      else -> null
    }

    private fun hasDependencies(jdk: Sdk): Boolean {
      return sdkDependencies.get(ModuleDependencyItem.SdkDependency(jdk.name, jdk.sdkType.name)).isNotEmpty()
             || jdk.name == projectRootManager.projectSdkName && jdk.sdkType.name == projectRootManager.projectSdkTypeName && hasProjectSdkDependency()
    }

    fun unsubscribeListeners() {
      watchedSdks.forEach {
        @Suppress("UNCHECKED_CAST")
        eventDispatcher.multicaster.removedDependencyOn((it as Supplier<Sdk>).get())
      }
      watchedSdks.clear()
    }
  }

}