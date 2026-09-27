# extsort — 受内存上限约束的外部排序工具

纯 JDK 8 标准库实现，无第三方依赖。对 UTF-8 制表符分隔（TSV）文本按指定列做
稳定排序，全程内存占用（按内部账本 `MemoryLedger` 估算）严格低于
`--max-memory-mb`。

## 构建

```sh
javac -encoding UTF-8 -d build $(find src -name '*.java')
```

## 用法

```sh
java -cp build com.gsb.extsort.Main sort \
    --input 输入路径 --output 输出路径 --key 列序号(从0开始) \
    --max-memory-mb 内存上限MB [--order asc|desc] [--bad-line skip|fail] \
    --temp-dir 临时目录
```

退出码：0 成功；1 遇到坏行且 `--bad-line fail`；2 参数或 IO 错误。

## 设计要点

- **分批读入**：从内存上限换算出每批预算 `(limit - IO_SLACK) / 2` 字节，
  逐行估算记录大小，超预算即排序落盘为一个有序 run 临时文件。
- **稳定排序**：每条记录携带原始行号 `seq`，批内按 `(key, seq)` 比较；
  归并时按 `(key, run 序号)` 比较（run 按输入顺序产生，序号即行号次序）。
- **受控多路归并**：归并路数 `fanIn = min(16, 归并预算 / 每路最小缓冲)`，
  每路一个文件句柄并持有 `wayBuffer` 预算；run 数超过 `fanIn` 时分批多轮归并，
  任意时刻打开的文件句柄不超过 `fanIn`。
- **内存账本**：批缓冲、每路归并缓冲、输出缓冲、IO 余量全部计入
  `MemoryLedger`，`ExternalSort.peakBytes()` 暴露峰值供测试断言。
- **坏行**：列数不足（key 列缺失）视为坏行。`skip` 计数并继续；`fail`
  立即抛出带行号和原因的 `BadLineException`，并删除输出文件与全部临时文件。

## 测试

```sh
./run-tests.sh
```

覆盖：远大于内存上限的输入排序并与全内存参照结果逐行比对、有序性与稳定性
校验、`peakBytes()` 上限断言、临时目录清空、输出文件失败不残留、
skip/fail 两种坏行策略及 fail 错误信息行号。
