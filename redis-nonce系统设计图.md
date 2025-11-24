```mermaid
sequenceDiagram
    participant Biz      as 业务系统\n(调用方服务)
    participant Facade   as Nonce组件门面
    participant Tpl      as 执行模板\n(拿号+执行业务+回收)
    participant EngineM  as 引擎管理器\n(路由与模式切换)
    participant Reliable as 可靠模式引擎\n(数据库为主)
    participant PerfEng  as 性能模式引擎\n(Redis为主, 批量預取)
    participant LockC    as 加锁协调器\n(基于Redis)
    participant Redis    as Redis\n(锁/计数器/預取池/可复用池/快照/队列)
    participant FlushW   as 刷盘任务\n(定时写回数据库)
    participant DB       as 数据库\n(Postgres)
    participant ModeCtrl as 模式管理入口\n(运维接口)
    participant Monitor  as 监控平台

    Biz->>Facade: 1 业务系统发起需要 nonce 的请求
    Facade->>Tpl: 2 执行模板接管本次请求
    Tpl->>EngineM: 3 为当前用户申请一个安全 nonce

    %% 可靠模式分支（蓝色背景）
    rect rgb(210,230,255)
    alt 当前为可靠模式
        EngineM->>Reliable: 4R 路由到可靠模式引擎
        Reliable->>LockC: 5R 为该用户申请分布式锁
        LockC->>Redis: 6R 在 Redis 中加锁
        Redis-->>LockC: 7R 返回加锁结果
        LockC-->>Reliable: 8R 锁成功后继续

        Reliable->>DB: 9R 在数据库事务中加载或初始化用户状态
        Reliable->>DB: 10R 优先复用可回收 nonce 否则使用本地计数生成新 nonce
        DB-->>Reliable: 11R 返回本次预留的 nonce 记录
        Reliable-->>EngineM: 12R 返回用户和 nonce
    else 当前为性能模式
    end
    end

    %% 性能模式分支（橙色背景，细化内部步骤）
    rect rgb(255,235,210)
    opt 当前为性能模式
        EngineM->>PerfEng: 4P 路由到性能模式引擎

        %% 1. 先看看有没有旧号可以复用
        PerfEng->>Redis: 5P.1 尝试从可复用池获取最早可用的旧 nonce
        Redis-->>PerfEng: 5P.2 返回可复用 nonce 或空

        alt 有可复用 nonce
            PerfEng->>PerfEng: 5P.3 使用该旧 nonce 作为本次分配结果
        else 没有可复用 nonce
            %% 2. 再从預取池拿批量預生成的新号
            PerfEng->>Redis: 5P.4 尝试从預取池获取一个預取 nonce
            Redis-->>PerfEng: 5P.5 返回預取 nonce 或空

            alt 預取池中有剩余
                PerfEng->>PerfEng: 5P.6 使用此預取 nonce
                PerfEng->>PerfEng: 5P.7 统计剩余量, 接近耗尽时触发下一批預取
            else 預取池为空或不足
                %% 3. 在锁内批量补货: 使用计数器一次生成一批新号
                PerfEng->>LockC: 5P.8 为该用户短暂加锁, 准备批量补货
                LockC->>Redis: 5P.9 在 Redis 中加锁
                Redis-->>LockC: 5P.10 锁成功
                LockC-->>PerfEng: 5P.11 可以安全执行批量操作

                PerfEng->>Redis: 5P.12 根据数据库中 nextLocalNonce\n初始化或校准计数器
                PerfEng->>Redis: 5P.13 使用计数器一次批量生成一段连续 nonce\n数量由配置决定, 例如 32 个
                Redis-->>PerfEng: 5P.14 返回这段新号区间的最大值
                PerfEng->>Redis: 5P.15 将这一批连续 nonce 依次写入預取池\n并设置过期时间
                PerfEng->>Redis: 5P.16 从新的預取池中取出本次要用的第一个 nonce
            end
        end

        %% 4. 写快照 + 写刷盘队列
        PerfEng->>Redis: 5P.17 为本次分配写入快照记录为預留状态
        PerfEng->>Redis: 5P.18 写入一条預留事件到刷盘队列\n用于后续异步落库
        PerfEng-->>EngineM: 5P.19 返回用户和本次分配的 nonce
    end
    end

    EngineM-->>Tpl: 13 返回 nonce 分配结果
    Tpl-->>Biz: 14 把 nonce 和上下文交给业务系统

    Biz->>Biz: 15 使用 nonce 执行业务操作
    Biz-->>Tpl: 16 返回业务结果\n(成功 / 失败 / 计划重试)
    Tpl->>EngineM: 17 请求根据业务结果更新 nonce 状态

    %% 可靠模式下的状态更新（蓝色背景）
    rect rgb(210,230,255)
    opt 当前为可靠模式
        EngineM->>Reliable: 18R 更新状态交给可靠引擎
        Reliable->>DB: 19R 在数据库事务中按业务结果更新状态\n成功标记已使用\n失败且不再重试标记可回收\n计划重试保持預留
        DB-->>Reliable: 20R 状态更新完成
        Reliable-->>EngineM: 21R 返回更新结果
    end
    end

    %% 性能模式下的状态更新（橙色背景，细化关键点）
    rect rgb(255,235,210)
    opt 当前为性能模式
        EngineM->>PerfEng: 18P 更新状态交给性能引擎
        PerfEng->>LockC: 19P 为该用户短暂加锁, 确保同一 nonce 串行更新
        LockC->>Redis: 20P 在 Redis 中加锁
        Redis-->>LockC: 21P 锁成功
        LockC-->>PerfEng: 22P 可以安全更新缓存

        PerfEng->>Redis: 23P.1 读取该 nonce 的快照
        alt 业务确认成功
            PerfEng->>Redis: 23P.2 将快照标记为已使用, 记录交易信息
            PerfEng->>Redis: 23P.3 从可复用池中移除该 nonce
            PerfEng->>Redis: 23P.4 写入一条已使用事件到刷盘队列
        else 明确失败且不再重试
            PerfEng->>Redis: 23P.5 将快照标记为可回收
            PerfEng->>Redis: 23P.6 将该 nonce 放入可复用池
            PerfEng->>Redis: 23P.7 写入一条可回收事件到刷盘队列
        else 业务计划自行重试
            PerfEng->>Redis: 23P.8 保持快照为預留状态, 必要时仅更新备注
        end

        PerfEng-->>EngineM: 24P 返回更新结果
    end
    end

    EngineM-->>Tpl: 25 通知模板本次 nonce 生命周期结束
    Tpl-->>Facade: 26 汇总业务结果和 nonce 使用情况
    Facade-->>Biz: 27 返回统一响应

    %% 刷盘后台任务（灰色背景）
    rect rgb(240,240,240)
    loop 定时刷盘
        FlushW->>Redis: 28 从刷盘队列批量取出待处理事件
        Redis-->>FlushW: 29 返回一批事件
        alt 有事件
            FlushW->>DB: 30 在一个数据库事务中依次应用事件\n创建預留记录 标记已使用 标记可回收
            alt 事务成功
                DB-->>FlushW: 31 落库成功
                FlushW->>Redis: 32 删除这些事件
                FlushW->>PerfEng: 33 通知性能引擎本批次成功\n可清理已终结快照并更新健康状态
            else 事务失败
                DB-->>FlushW: 31e 事务回滚
                FlushW->>Redis: 32e 将事件退回队列等待重试
                FlushW->>PerfEng: 33e 通知性能引擎本批次失败\n记录异常并更新健康指标
            end
        else 无事件
            FlushW-->>FlushW: 30b 本轮无任务
        end
    end
    end

    %% 模式管理与监控（淡蓝背景）
    rect rgb(235,245,255)
    ModeCtrl->>EngineM: 34 查询当前模式或发起切换
    EngineM-->>ModeCtrl: 35 返回当前模式信息\n可靠 性能 双写 排水 降级

    ModeCtrl->>EngineM: 36 例如请求从可靠升级到性能 或 从性能回退到可靠
    EngineM->>PerfEng: 37 切到性能前询问条件是否满足\n队列是否已清空 Redis 是否健康
    PerfEng-->>EngineM: 38 返回检查结果
    EngineM->>EngineM: 39 条件满足时更新内部模式状态\n记录切换时间和日志

    PerfEng-->>Monitor: 40 提供性能链路健康数据\nRedis状况 队列长度 最近成功失败时间 預取池耗尽情况
    EngineM-->>Monitor: 41 提供当前模式和最近一次模式切换信息
    Monitor-->>EngineM: 42 运维根据看板决定是否调整模式和預取策略
    end
    ```