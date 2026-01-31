#[test]
fn compile_test() {
    let t = trybuild::TestCases::new();
    // 成功するケース
    t.compile_fail("tests/derive_success.rs");
    // repr(C) がない場合の失敗ケース
    t.compile_fail("tests/no_repr_c.rs");
    // Clone がない場合の失敗ケース
    t.compile_fail("tests/no_clone_derive.rs");
}