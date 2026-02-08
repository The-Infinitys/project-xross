# Xross (クロス)

Xross は、Rust と JVM (Kotlin/Java) 間のシームレスで高性能、かつメモリ安全な相互運用性を提供する次世代フレームワークです。

Java 25 で導入された **Project Panama (Foreign Function & Memory API)** を活用し、従来の JNI (Java Native Interface) における複雑なボイラープレートやオーバーヘッドを排除します。

## 主な特徴

*   **高性能**: FFM API を使用した直接的なネイティブメモリ操作と関数呼び出しにより、JNI を超えるパフォーマンスを実現します。
*   **Rust の所有権モデルを JVM へ**: Rust の `Owned`, `Ref`, `MutRef` セマンティクスをメタデータとして抽出し、Kotlin 側のコード生成に反映。メモリ安全性を言語境界を越えて保証します。
*   **自動バインディング生成**: Rust の構造体やメソッドにアノテーションを付与するだけで、スレッドセーフで型安全な Kotlin クラスを自動生成します。
*   **柔軟なリソース管理**: `Arena.ofAuto()` による自動メモリ管理に加え、`AutoCloseable` による明示的な解放もサポート。
*   **高度な型サポート**: 
    *   構造体 (Struct) およびインライン構造体
    *   列挙型 (Enum / Algebraic Data Types)
    *   不透明型 (Opaque Types)
    *   アトミックなフィールドアクセス (`StampedLock` や `VarHandle` を活用)

## プロジェクト構成

*   `xross-core`: Rust 側のランタイムおよびアノテーション定義。
*   `xross-macros`: `#[derive(JvmClass)]` や `#[jvm_class]` などのコード生成マクロ。
*   `xross-metadata`: 言語間で共有される型情報のシリアライズ定義。
*   `xross-plugin`: Kotlin バインディングを生成する Gradle プラグイン。
*   `xross-example`: 実装例と統合テスト。

## クイックスタート

### 1. Rust 側の設定

`Cargo.toml` に `xross-core` を追加します。

```toml
[dependencies]
xross-core = { path = "path/to/xross-core" }
```

Rust のコードで公開したい型とメソッドを定義します。

```rust
use xross_core::{JvmClass, jvm_class};

#[derive(JvmClass, Clone)]
pub struct MyService {
    #[jvm_field]
    pub val: i32,
}

#[jvm_class]
impl MyService {
    #[jvm_new]
    pub fn new(val: i32) -> Self {
        Self { val }
    }

    #[jvm_method]
    pub fn calculate(&self, factor: i32) -> i32 {
        self.val * factor
    }
}
```

### 2. Kotlin 側の設定 (Gradle)

`build.gradle.kts` に `org.xross` プラグインを適用します。

```kotlin
plugins {
    id("org.xross") version "0.1.0"
}

xross {
    rustProjectDir = "../rust-lib"    // Rust プロジェクトのルート
    packageName = "org.example.xross" // 生成される Kotlin のパッケージ名
}
```

### 3. ビルドと実行

Gradle タスクを実行して Kotlin バインディングを生成します。

```bash
./gradlew generateXrossBindings
```

生成されたバインディングを使用して Kotlin から Rust を呼び出します。

```kotlin
import org.example.xross.MyService

fun main() {
    // インスタンス作成 (Rust の MyService::new が呼ばれる)
    MyService(10).use { service ->
        println("Value: ${service.val}")
        
        val result = service.calculate(5)
        println("Result: $result") // 50
    } // スコープを抜けると自動的に Rust 側のメモリが解放される
}
```

## 必要条件

*   **Rust**: 1.80+ (Nightly 推奨)
*   **Java**: 25+ (FFM API がプレビューまたは標準機能として利用可能なバージョン)
*   **Gradle**: 8.0+

### 実行時の注意
FFM API (Project Panama) を使用するため、実行時には以下の JVM 引数が必要です。

```bash
--enable-native-access=ALL-UNNAMED
```

Gradle プロジェクトでは、以下のように設定できます。

```kotlin
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
```

## 安全性と所有権 (Safety & Ownership)

Xross は Rust の借用チェッカーの概念を Kotlin に持ち込みます。

*   **Owned**: Kotlin 側で `use` ブロックや `close()` を通じてライフサイクルを管理します。
*   **Ref (`&T`) / MutRef (`&mut T`)**: Rust 側で管理されている参照を Kotlin から安全に借用します。親オブジェクトが解放されると、これらの参照へのアクセスは自動的に無効化され、`NullPointerException` がスローされます。
*   **スレッド安全**:
    *   `safety = Atomic`: `VarHandle` を使用したロックフリーなアトミック操作を提供します。
    *   `safety = Lock`: `StampedLock` を使用した楽観的読み取りと書き込みロックを提供します。

## 高度な利用法

### 不透明型 (Opaque Types)
Rust 側の詳細を隠蔽したまま Kotlin へ渡すことができます。

```rust
pub struct InternalData { /* ... */ }
xross_core::opaque_class!(com.example, InternalData);
```

### パッケージ構成
`#[jvm_package]` を使用して、生成される Kotlin クラスのパッケージを個別に指定可能です。

```rust
#[derive(JvmClass)]
#[jvm_package("com.example.service")]
pub struct AdvancedService;
```

## 開発状況

現在、Project Panama の最新機能を活用したメモリ管理モデルへの移行を進めています。
