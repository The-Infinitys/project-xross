# Xross Architecture Specification (v2.0.0)

Xross は、Rust と JVM 間の究極の相互運用性を目指したフレームワークです。Java 25 で導入された **Foreign Function & Memory (FFM) API** を基盤とし、Rust の所有権セマンティクスを Java/Kotlin へ安全に持ち込むための高度な抽象化を提供します。

---

## 1. コア・デザイン・フィロソフィー

### 1.1 Zero-Overhead Bridge
JNI のような中間レイヤーや複雑な変換コードを最小化し、MethodHandle と MemorySegment を通じた直接的なネイティブアクセスを実現します。

### 1.2 Ownership-Aware Interop
Rust の最大の特徴である所有権（Ownership）と借用（Borrowing）の概念を、Kotlin の型システムとライフサイクル管理（AutoCloseable）にマッピングします。

### 1.3 Thread Safety by Design
ネイティブメモリへのアクセスに対し、Rust 側の型情報に基づいた適切な同期メカニズム（Lock, Atomic, Immutable）を自動的に付与します。

---

## 2. 技術スタック

- **Java/Kotlin**: Java 25+ (FFM API / Project Panama)
- **Rust**: Edition 2024
- **Build System**: Gradle + Cargo
- **Code Generation**: KotlinPoet + `syn`/`quote` (Proc-macros)
- **Serialization**: Serde (Metadata exchange)

---

## 3. 型システムとマッピング

### 3.1 プリミティブ
Rust の基本型は FFM API の `ValueLayout` を通じて直接マッピングされます。

| Rust Type | Kotlin Type | ValueLayout |
| :--- | :--- | :--- |
| `i8` / `u8` | `Byte` | `JAVA_BYTE` |
| `i32` | `Int` | `JAVA_INT` |
| `i64` / `isize` | `Long` | `JAVA_LONG` |
| `bool` | `Boolean` | `JAVA_BYTE` (0/1) |
| `f32` | `Float` | `JAVA_FLOAT` |
| `f64` | `Double` | `JAVA_DOUBLE` |
| `String` | `String` | `ADDRESS` (UTF-8) |

### 3.2 複合型

#### 構造体 (Struct)
`#[derive(XrossClass)]` を付与された Rust 構造体は、Kotlin のクラスとして生成されます。
- **インラインフィールド**: 構造体内に直接配置されるデータ。
- **ポインタフィールド**: 他のオブジェクトへの参照。

#### 列挙型 (Enum / ADT)
Rust の列挙型は、Kotlin の `sealed class`（データを持つ場合）または `enum class`（純粋な列挙型の場合）に変換されます。

#### 不透明型 (Opaque Types)
内部構造を JVM 側に露出させず、ポインタとしてのみ管理する型です。

---

## 4. メモリ管理モデル

Xross は `java.lang.foreign.Arena` を活用してメモリのライフサイクルを管理します。

### 4.1 所有権のマッピング

- **Owned (`T`)**: 
    - Rust 側から「所有権付き」で返されたオブジェクト。
    - Kotlin 側で `Arena.ofConfined()` が生成され、`close()` 時に Rust 側の `drop` 関数が呼ばれます。
- **Ref (`&T`)**:
    - 他のオブジェクトから借用された不変参照。
    - 親オブジェクトの `AliveFlag` を共有し、親が解放された後のアクセスは `NullPointerException` をスローします。
- **MutRef (`&mut T`)**:
    - 可変参照。Kotlin 側で書き込み操作が許可されます。
- **Async (`async fn`)**:
    - Rust の `Future` は、Kotlin 側の `XrossTask` 構造体にマッピングされ、最終的に `suspend` 関数としてラップされます。
    - 内部的なポーリングループにより、Coroutines の非ブロッキングな待機を実現します。

### 4.2 文字列の扱い
Rust の `String` はヒープ確保されるため、呼び出しごとに `Arena` を通じてコピーまたは確保が行われます。戻り値としての `String` は、Xross が提供する専用の Free 関数によって解放されます。

---

## 5. スレッド安全性 (Thread Safety)

メタデータとして定義された `ThreadSafety` レベルに基づき、生成されるコードの挙動が変わります。

1. **Unsafe**: 同期なし。最高速ですが、スレッド間での共有はユーザーの責任となります。
2. **Lock**: `java.util.concurrent.locks.StampedLock` を使用。
    - `&T` (Ref) によるアクセスは楽観的読み取りまたは共有ロック。
    - `&mut T` (MutRef) または Owned によるアクセスは排他ロック。
3. **Atomic**: `java.lang.invoke.VarHandle` を使用した CAS 操作。
4. **Immutable**: 初期化後は不変であることを保証。同期コストなしでスレッド間共有が可能。

---

## 6. ビルドプロセス

1. **Rust Analysis**: `xross-macros` がコードを解析し、`xross-metadata` 形式の JSON を出力。
2. **Gradle Task**: `xross-plugin` が JSON を読み込み、KotlinPoet を使用してバインディングクラスを生成。
3. **Linking**: 実行時に `System.loadLibrary`（または Panama の `SymbolLookup`）を使用して Rust 側のシンボルを解決。

---

## 7. 今後の展望

- **Callback**: JVM 側の関数を Rust 側から呼び出すための Upcall サポート。
- **Collection**: `Vec<T>` と `List<T>` のシームレスな共有。
- **Memory View**: `&[u8]` などのスライスを `MemorySegment` として効率的に共有。
- **Static Analysis**: Rust 側の型定義と Kotlin 側のバインディングの整合性を検証するビルド時チェックの強化。

---

## 8. パフォーマンス最適化の原則 (Performance Principles)

Xross を使用して最高のパフォーマンスを引き出すための、実証に基づいた 3 つの原則です。

### 8.1 アロケーションを最小化せよ
Native 側で頻繁に小規模なメモリ確保・解放を行うと、JVM の高度に最適化されたメモリ管理（TLAB: Thread Local Allocation Buffer）に劣る場合があります。Native 側でバッファを再利用する設計にすることで、真の性能が発揮されます。

### 8.2 キャッシュを意識せよ
構造体の配列を扱う場合などは、ポインタの配列（`Vec<Vec<T>>`）ではなくデータを平坦化（Flattening）した 1 次元配列を使用してください。CPU が先読みしやすい連続したメモリアクセスを行うことで、Native の計算能力を最大限に引き出せます。

### 8.3 1 回の処理を重くせよ
FFI 境界（JNI/FFM）の往復にはマイクロ秒単位の不可避なオーバーヘッドが存在します。Rust 側での実行時間が十分長くなるように処理をまとめることで、この境界コストは相対的に無視できるレベル（誤差の範囲）になります。
