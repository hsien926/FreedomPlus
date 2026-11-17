package io.github.fplus.plugin.base

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.contextaware.ContextAware
import androidx.activity.contextaware.ContextAwareHelper
import androidx.activity.contextaware.OnContextAvailableListener
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.CallSuper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

open class BaseActivity : Activity(),
    IPluginActivity,
    ContextAware,
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {
    private val mContextAwareHelper = ContextAwareHelper()

    // lifecycle
    private var _lifecycleRegistry: LifecycleRegistry? = null

    private val lifecycleRegistry: LifecycleRegistry
        get() = _lifecycleRegistry ?: LifecycleRegistry(this)
            .also { _lifecycleRegistry = it }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    // view model
    private var _viewModelStore: ViewModelStore? = null

    override val viewModelStore: ViewModelStore
        get() = _viewModelStore ?: (lastNonConfigurationInstance as? ViewModelStore ?: ViewModelStore())
            .also { _viewModelStore = it }

    @Deprecated("Deprecated in Java")
    override fun onRetainNonConfigurationInstance(): Any? = _viewModelStore

    // instance state
    private var _savedStateRegistryController: SavedStateRegistryController? = null

    private val savedStateRegistryController
        get() = _savedStateRegistryController ?: SavedStateRegistryController.create(this)
            .also { _savedStateRegistryController = it }

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // back pressed
    override val onBackPressedDispatcher = OnBackPressedDispatcher {
        super.onBackPressed()
    }

    override fun addOnContextAvailableListener(listener: OnContextAvailableListener) {
        mContextAwareHelper.addOnContextAvailableListener(listener)
    }

    override fun peekAvailableContext(): Context? {
        return mContextAwareHelper.peekAvailableContext()
    }

    override fun removeOnContextAvailableListener(listener: OnContextAvailableListener) {
        mContextAwareHelper.removeOnContextAvailableListener(listener)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("onBackPressedDispatcher.onBackPressed()"))
    @CallSuper
    override fun onBackPressed() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedStateRegistryController.performSave(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 保存状态使用插件加载器；不删除并非真实 provider key 的 Compose 名称。
        val restored_state = try {
            savedInstanceState?.let { original ->
                Bundle(original).apply {
                    classLoader = this@BaseActivity.classLoader
                    keySet() // 提前展开，损坏 Parcel 不进入已开始恢复的 registry。
                    getBundle("androidx.lifecycle.BundlableSavedStateRegistry.key")?.apply {
                        classLoader = this@BaseActivity.classLoader
                        keySet()
                    }
                }
            }
        } catch (error: android.os.BadParcelableException) {
            // 设置本身在 MMKV；仅放弃不可反序列化的临时界面状态。
            null
        }
        savedStateRegistryController.performRestore(restored_state)
        mContextAwareHelper.dispatchOnContextAvailable(this)
        super.onCreate(restored_state)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        super.onPause()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onStop() {
        super.onStop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        mContextAwareHelper.clearAvailableContext()
        if (!isChangingConfigurations) {
            _viewModelStore?.clear()
        }
    }

    override fun setContentView(layoutResID: Int) {
        initViewTreeOwners()
        super.setContentView(layoutResID)
    }

    override fun setContentView(view: View) {
        initViewTreeOwners()
        super.setContentView(view)
    }

    override fun setContentView(view: View, params: ViewGroup.LayoutParams?) {
        initViewTreeOwners()
        super.setContentView(view, params)
    }

    override fun addContentView(view: View, params: ViewGroup.LayoutParams?) {
        initViewTreeOwners()
        super.addContentView(view, params)
    }

    private fun initViewTreeOwners() {
        window!!.decorView.setViewTreeViewModelStoreOwner(this)
        window!!.decorView.setViewTreeLifecycleOwner(this)
        window!!.decorView.setViewTreeSavedStateRegistryOwner(this)
        window!!.decorView.setViewTreeOnBackPressedDispatcherOwner(this)
    }
}