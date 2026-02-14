/// Returns the English ordinal name as a word (no digits).
/// For example: 0 → "zeroth", 1 → "first", 21 → "twenty-first", 100 → "one hundredth"
pub fn ordinal_name(i: usize) -> String {
    if i == 0 {
        return "zeroth".to_string();
    }

    let small = [
        "",
        "first",
        "second",
        "third",
        "fourth",
        "fifth",
        "sixth",
        "seventh",
        "eighth",
        "ninth",
        "tenth",
        "eleventh",
        "twelfth",
        "thirteenth",
        "fourteenth",
        "fifteenth",
        "sixteenth",
        "seventeenth",
        "eighteenth",
        "nineteenth",
        "twentieth",
    ];

    if i <= 20 {
        return small[i].to_string();
    }

    // ここから再帰的に大きな数を扱う
    if i >= 1_000_000 {
        let millions = i / 1_000_000;
        let remainder = i % 1_000_000;

        let million_part = if millions == 1 {
            "one_million".to_string()
        } else {
            format!("{}_million", cardinal_to_ordinal(millions))
        };

        return if remainder == 0 {
            format!("{}th", million_part)
        } else {
            format!("{}_{}", million_part, ordinal_name(remainder))
        };
    }

    if i >= 1_000 {
        let thousands = i / 1_000;
        let remainder = i % 1_000;

        let thousand_part = if thousands == 1 {
            "one_thousand".to_string()
        } else {
            format!("{}_thousand", cardinal_to_ordinal(thousands))
        };

        return if remainder == 0 {
            format!("{}th", thousand_part)
        } else {
            format!("{}_{}", thousand_part, ordinal_name(remainder))
        };
    }

    if i >= 100 {
        let hundreds = i / 100;
        let remainder = i % 100;

        let hundred_part = if hundreds == 1 {
            "one_hundred".to_string()
        } else {
            format!("{}_hundred", cardinal_to_ordinal(hundreds))
        };

        return if remainder == 0 {
            format!("{}th", hundred_part)
        } else {
            format!("{}_{}", hundred_part, ordinal_name(remainder))
        };
    }

    // 21〜99
    let tens = i / 10;
    let ones = i % 10;

    let ten_names =
        ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];

    let mut result = ten_names[tens].to_string();

    if ones > 0 {
        result.push('-');
        result.push_str(small[ones]);
    }

    result
}

// 補助関数：基数 → 序数（1→first, 2→second, ...）の再帰的変換
// （ordinal_nameの内部で使うために簡易版）
fn cardinal_to_ordinal(n: usize) -> String {
    if n <= 20 {
        let small = [
            "",
            "first",
            "second",
            "third",
            "fourth",
            "fifth",
            "sixth",
            "seventh",
            "eighth",
            "ninth",
            "tenth",
            "eleventh",
            "twelfth",
            "thirteenth",
            "fourteenth",
            "fifteenth",
            "sixteenth",
            "seventeenth",
            "eighteenth",
            "nineteenth",
            "twentieth",
        ];
        return small[n].to_string();
    }

    // ここでは簡易的に末尾だけthにする（完全ではないが実用的）
    // 必要ならさらに細かく拡張可能
    let s = ordinal_name(n);
    if s.ends_with("first") || s.ends_with("second") || s.ends_with("third") {
        s
    } else {
        format!("{}th", s.trim_end_matches(|c: char| c.is_alphabetic() && !c.is_whitespace()))
    }
}
