package io.github.imroxy2222.lsp_colorOSRecord

import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// ---------------------------------------------------------------- DexKit 锚点（17.8.8 反编译，已验证唯一性）

/** AISummaryTaskManager#register 回调方法内的日志串 —— 全 app 唯一（q.g）。 */
private const val ANCHOR_REGISTER = "registerAISummaryCallback has container call back"

/** AISummaryTaskManager#unregister 回调方法内的日志串 —— 全 app 唯一（q.i）。 */
private const val ANCHOR_UNREGISTER = "unregisterAISummaryCallback ui callback is null"

/** AISummaryTaskManager#cancel 方法内日志串（q.b）—— 其他 TaskManager 有同款句式，
 *  全局不唯一，必须再用「声明类 == q」+ 签名 (long)->boolean 过滤
 *  （q.d(long) 同签名但纯 containsKey 无字符串，可借此区分）。 */
private const val ANCHOR_CANCEL = "maps not contains mediaId: "

/** 摘要缓存查询 SQL —— 精确匹配 database.b#f(long)（DAO f 里是加了 recycle/bundle 条件的长串，不同常量）。 */
private const val ANCHOR_CACHE_SQL =
    "SELECT * FROM summary_cache_data WHERE media_id = ? AND choose_state = 0"

/** 摘要事件回调接口的方法名（接口方法未混淆；实现类遍布各处，须限定 INTERFACE）。 */
private const val M_CB_FINISHED = "onAISummaryFinished"
private const val M_CB_ERROR = "onAISummaryError"

/**
 * 摘要管道混淆符号的 DexKit 兜底定位器（SummaryQueue 层2）。
 *
 * 层1（SummaryQueue.resolveKnownNames）按 17.8.8 已知混淆名（q.g/h/i/b、u、model.o、
 * database.b#f）定位失败 —— 典型场景是新版本 R8 重排单字母名 —— 时，用 DexKit 按
 * 字符串锚点 / 结构特征重定位，结果由 SummaryQueue 进程内缓存（每进程至多跑一次，
 * 且仅在层1失败时才启动，正常路径零开销）。
 *
 * 查得类/方法后全部做签名校验再放行，避免锚点撞车误定位。
 */
internal object SummaryDexKitResolver {

    /**
     * 执行层2定位。任何一步失败都返回 null（摘要功能降级关闭，不影响转文本）。
     *
     * @param themeCls 未混淆的 SummaryTheme 类（model 构造器第 4 参类型，用于钉死签名）
     */
    fun find(cl: ClassLoader, apkPath: String, themeCls: Class<*>): SummaryQueue.ObfuscatedDeps? {
        return try {
            DexKitBridge.create(apkPath).use { bridge -> findWith(bridge, cl, themeCls) }
        } catch (t: Throwable) {
            XLog.w("summary dexkit resolve failed: ${t.message}")
            null
        }
    }

    private fun findWith(
        bridge: DexKitBridge,
        cl: ClassLoader,
        themeCls: Class<*>
    ): SummaryQueue.ObfuscatedDeps? {
        // 1) q 类 + register(g)：日志串全局唯一
        val registerData = bridge.findMethod {
            matcher { usingStrings(ANCHOR_REGISTER) }
        }.firstOrNull() ?: run {
            XLog.w("summary dexkit: register anchor not found")
            return null
        }
        val qCls = try {
            Class.forName(registerData.className, false, cl)
        } catch (t: Throwable) {
            XLog.w("summary dexkit: q class load failed: ${t.message}")
            return null
        }
        val register = registerData.getMethodInstance(cl)

        // 2) u 接口：同时声明 onAISummaryFinished / onAISummaryError 的接口（实现类须排除）
        val uCls = bridge.findClass {
            matcher {
                modifiers(Modifier.INTERFACE)
                methods {
                    add { name(M_CB_FINISHED) }
                    add { name(M_CB_ERROR) }
                }
            }
        }.firstOrNull()?.let {
            try { Class.forName(it.name, false, cl) } catch (_: Throwable) { null }
        } ?: run {
            XLog.w("summary dexkit: callback interface not found")
            return null
        }

        // 3) model 类：10 参构造器（第 4 参 SummaryTheme 钉死包路径，签名唯一）
        val modelCls = bridge.findClass {
            matcher {
                methods {
                    add {
                        name("<init>")
                        paramTypes(
                            "long", "int", "long", themeCls.name, "long",
                            "java.lang.String", "java.lang.String", "int", "boolean", "int"
                        )
                    }
                }
            }
        }.firstOrNull()?.let {
            try { Class.forName(it.name, false, cl) } catch (_: Throwable) { null }
        } ?: run {
            XLog.w("summary dexkit: request model class not found")
            return null
        }

        // 4) unregister(i)：q 内静态 (long, u)->void 且异于 register（该签名仅 g/i 两个）
        val unregister = qCls.declaredMethods
            .filter {
                Modifier.isStatic(it.modifiers) && it.name != register.name &&
                    it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE, uCls))
            }
            .singleOrNull() ?: run {
            XLog.w("summary dexkit: unregister ambiguous")
            return null
        }

        // 5) post(h)：q 内静态、单参 model、返回 boolean（该签名唯一）
        val post = qCls.declaredMethods
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == modelCls &&
                    it.returnType == java.lang.Boolean.TYPE
            }
            .singleOrNull() ?: run {
            XLog.w("summary dexkit: post ambiguous")
            return null
        }

        // 6) cancel(b)：锚点串 + 签名 (long)->boolean + 声明类限定 q（全局串不唯一，见常量注释）
        val cancel = bridge.findMethod {
            matcher {
                usingStrings(ANCHOR_CANCEL)
                paramTypes("long")
                returnType("boolean")
                modifiers(Modifier.STATIC)
            }
        }.firstOrNull { it.className == qCls.name }?.getMethodInstance(cl) ?: run {
            XLog.w("summary dexkit: cancel not found")
            return null
        }

        // 7) cacheGet(database.b#f)：SQL 串精确匹配 + (long) 参数
        val cacheGet = bridge.findMethod {
            matcher {
                usingStrings(ANCHOR_CACHE_SQL)
                paramTypes("long")
            }
        }.firstOrNull()?.getMethodInstance(cl) ?: run {
            XLog.w("summary dexkit: cache query not found")
            return null
        }

        // 8) 签名终检：全部对齐 17.8.8 观测到的形状才放行
        val ok = register.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE, uCls)) &&
            register.returnType == Void.TYPE &&
            unregister.returnType == Void.TYPE &&
            Modifier.isStatic(cancel.modifiers) &&
            cacheGet.returnType.name == CLS_SUMMARY_CACHE_ENTITY
        if (!ok) {
            XLog.w("summary dexkit: signature mismatch, reject")
            return null
        }

        val modelCtor: Constructor<*> = try {
            modelCls.getConstructor(
                java.lang.Long.TYPE, Integer.TYPE, java.lang.Long.TYPE, themeCls,
                java.lang.Long.TYPE, String::class.java, String::class.java,
                Integer.TYPE, java.lang.Boolean.TYPE, Integer.TYPE
            )
        } catch (t: Throwable) {
            XLog.w("summary dexkit: model ctor mismatch: ${t.message}")
            return null
        }

        XLog.i(
            "summary dexkit resolved: q=${qCls.name}(${register.name}/${post.name}/" +
                "${unregister.name}/${cancel.name}) u=${uCls.name} model=${modelCls.name} " +
                "cacheDb=${cacheGet.declaringClass.name}#${cacheGet.name}"
        )
        return SummaryQueue.ObfuscatedDeps(uCls, register, unregister, post, cancel, cacheGet, modelCtor)
    }
}
