//! Internally tagged enums (`{"t": "hello", …fields}`) without serde's `Content` buffer.
//!
//! `#[serde(tag = "…")]` deserializes by first buffering the input into serde's private
//! `Content` tree, with a copy of that machinery for every enum and every input type: most of
//! the web core's `Content` code, ~150 KB of wasm before gzip (R25, NOTES.md). Here an enum
//! derives the plain externally tagged form for itself only (`#[serde(remote = "Self")]`), and
//! [`tagged!`](crate::tagged!) adds the trait impls: serializing writes the tag as the first
//! map entry (the same bytes as before), deserializing reads a `serde_json::Value` (one
//! instantiation shared by everything) and hands the variant its remaining fields.
//!
//! Only unit and struct variants are supported, which is all the tagged enums here have.

use serde::de::{self, DeserializeSeed, IntoDeserializer, Visitor};
use serde::ser::{self, Impossible, SerializeMap, Serializer};
use serde_json::{Map, Value};

/// `impl Serialize + Deserialize` for an enum declared with
/// `#[derive(Serialize, Deserialize)] #[serde(remote = "Self", …)]`, internally tagged by `$tag`.
#[macro_export]
macro_rules! tagged {
    ($ty:ident, $tag:literal) => {
        impl serde::Serialize for $ty {
            fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
                $ty::serialize(self, $crate::tagged::Ser { tag: $tag, inner: s })
            }
        }
        impl<'de> serde::Deserialize<'de> for $ty {
            fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
                let value = <serde_json::Value as serde::Deserialize>::deserialize(d)?;
                $ty::deserialize($crate::tagged::De { tag: $tag, value }).map_err(serde::de::Error::custom)
            }
        }
    };
}

/// Reads one internally tagged enum out of a JSON value.
pub struct De {
    pub tag: &'static str,
    pub value: Value,
}

impl<'de> de::Deserializer<'de> for De {
    type Error = serde_json::Error;

    fn deserialize_enum<V: Visitor<'de>>(
        self,
        _name: &'static str,
        _variants: &'static [&'static str],
        visitor: V,
    ) -> Result<V::Value, Self::Error> {
        let Value::Object(mut map) = self.value else {
            return Err(de::Error::invalid_type(unexpected(&self.value), &"a map"));
        };
        match map.remove(self.tag) {
            Some(Value::String(variant)) => visitor.visit_enum(Access { variant, rest: map }),
            Some(other) => Err(de::Error::invalid_type(unexpected(&other), &"a string")),
            None => Err(de::Error::missing_field(self.tag)),
        }
    }

    fn deserialize_any<V: Visitor<'de>>(self, _visitor: V) -> Result<V::Value, Self::Error> {
        Err(de::Error::custom("expected an internally tagged enum"))
    }

    serde::forward_to_deserialize_any! {
        bool i8 i16 i32 i64 i128 u8 u16 u32 u64 u128 f32 f64 char str string bytes byte_buf option
        unit unit_struct newtype_struct seq tuple tuple_struct map struct identifier ignored_any
    }
}

fn unexpected(v: &Value) -> de::Unexpected<'_> {
    match v {
        Value::Null => de::Unexpected::Unit,
        Value::Bool(b) => de::Unexpected::Bool(*b),
        Value::Number(_) => de::Unexpected::Other("a number"),
        Value::String(s) => de::Unexpected::Str(s),
        Value::Array(_) => de::Unexpected::Seq,
        Value::Object(_) => de::Unexpected::Map,
    }
}

struct Access {
    variant: String,
    rest: Map<String, Value>,
}

impl<'de> de::EnumAccess<'de> for Access {
    type Error = serde_json::Error;
    type Variant = Rest;

    fn variant_seed<V: DeserializeSeed<'de>>(self, seed: V) -> Result<(V::Value, Rest), Self::Error> {
        let d: de::value::StringDeserializer<serde_json::Error> = self.variant.into_deserializer();
        Ok((seed.deserialize(d)?, Rest(self.rest)))
    }
}

struct Rest(Map<String, Value>);

impl<'de> de::VariantAccess<'de> for Rest {
    type Error = serde_json::Error;

    fn unit_variant(self) -> Result<(), Self::Error> {
        Ok(()) // other fields are ignored, as serde's tagged form does
    }

    fn newtype_variant_seed<T: DeserializeSeed<'de>>(self, _seed: T) -> Result<T::Value, Self::Error> {
        Err(de::Error::custom("newtype variants aren't supported by tagged!"))
    }

    fn tuple_variant<V: Visitor<'de>>(self, _len: usize, _visitor: V) -> Result<V::Value, Self::Error> {
        Err(de::Error::custom("tuple variants aren't supported by tagged!"))
    }

    fn struct_variant<V: Visitor<'de>>(
        self,
        _fields: &'static [&'static str],
        visitor: V,
    ) -> Result<V::Value, Self::Error> {
        de::Deserializer::deserialize_map(Value::Object(self.0), visitor)
    }
}

/// Writes one internally tagged enum: the tag first, then the variant's fields.
pub struct Ser<S> {
    pub tag: &'static str,
    pub inner: S,
}

fn unsupported<E: ser::Error>() -> E {
    E::custom("tagged! serializes only unit and struct variants")
}

impl<S: Serializer> Serializer for Ser<S> {
    type Ok = S::Ok;
    type Error = S::Error;
    type SerializeSeq = Impossible<S::Ok, S::Error>;
    type SerializeTuple = Impossible<S::Ok, S::Error>;
    type SerializeTupleStruct = Impossible<S::Ok, S::Error>;
    type SerializeTupleVariant = Impossible<S::Ok, S::Error>;
    type SerializeMap = Impossible<S::Ok, S::Error>;
    type SerializeStruct = Impossible<S::Ok, S::Error>;
    type SerializeStructVariant = Fields<S::SerializeMap>;

    fn serialize_unit_variant(
        self,
        _name: &'static str,
        _index: u32,
        variant: &'static str,
    ) -> Result<S::Ok, S::Error> {
        let mut map = self.inner.serialize_map(Some(1))?;
        map.serialize_entry(self.tag, variant)?;
        map.end()
    }

    fn serialize_struct_variant(
        self,
        _name: &'static str,
        _index: u32,
        variant: &'static str,
        len: usize,
    ) -> Result<Self::SerializeStructVariant, S::Error> {
        let mut map = self.inner.serialize_map(Some(len + 1))?;
        map.serialize_entry(self.tag, variant)?;
        Ok(Fields(map))
    }

    fn serialize_bool(self, _: bool) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_i8(self, _: i8) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_i16(self, _: i16) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_i32(self, _: i32) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_i64(self, _: i64) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_u8(self, _: u8) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_u16(self, _: u16) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_u32(self, _: u32) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_u64(self, _: u64) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_f32(self, _: f32) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_f64(self, _: f64) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_char(self, _: char) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_str(self, _: &str) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_bytes(self, _: &[u8]) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_none(self) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_some<T: ?Sized + ser::Serialize>(self, _: &T) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_unit(self) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_unit_struct(self, _: &'static str) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_newtype_struct<T: ?Sized + ser::Serialize>(self, _: &'static str, _: &T) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_newtype_variant<T: ?Sized + ser::Serialize>(
        self,
        _: &'static str,
        _: u32,
        _: &'static str,
        _: &T,
    ) -> Result<S::Ok, S::Error> {
        Err(unsupported())
    }
    fn serialize_seq(self, _: Option<usize>) -> Result<Self::SerializeSeq, S::Error> {
        Err(unsupported())
    }
    fn serialize_tuple(self, _: usize) -> Result<Self::SerializeTuple, S::Error> {
        Err(unsupported())
    }
    fn serialize_tuple_struct(self, _: &'static str, _: usize) -> Result<Self::SerializeTupleStruct, S::Error> {
        Err(unsupported())
    }
    fn serialize_tuple_variant(
        self,
        _: &'static str,
        _: u32,
        _: &'static str,
        _: usize,
    ) -> Result<Self::SerializeTupleVariant, S::Error> {
        Err(unsupported())
    }
    fn serialize_map(self, _: Option<usize>) -> Result<Self::SerializeMap, S::Error> {
        Err(unsupported())
    }
    fn serialize_struct(self, _: &'static str, _: usize) -> Result<Self::SerializeStruct, S::Error> {
        Err(unsupported())
    }
}

/// A struct variant's fields, written into the map that holds the tag.
pub struct Fields<M>(M);

impl<M: SerializeMap> ser::SerializeStructVariant for Fields<M> {
    type Ok = M::Ok;
    type Error = M::Error;

    fn serialize_field<T: ?Sized + ser::Serialize>(&mut self, key: &'static str, value: &T) -> Result<(), M::Error> {
        self.0.serialize_entry(key, value)
    }

    fn end(self) -> Result<M::Ok, M::Error> {
        self.0.end()
    }
}

#[cfg(test)]
mod tests {
    use crate::feed::{Expr, FromRef, TimeRef};
    use crate::notify::TimeRule;
    use crate::stage::TimeOverride;
    use crate::sync::Frame;
    use crate::text::{Entity, EntityKind, MentionTarget};

    #[test]
    fn writes_the_tag_first_and_the_same_bytes_as_serde_tag() {
        let e = Expr::And {
            args: vec![
                Expr::From { from: FromRef::Name { name: "kai".into() } },
                Expr::Since { at: TimeRef::Ago { ms: 5 } },
                Expr::Not { arg: Box::new(Expr::Reply { value: true }) },
            ],
        };
        assert_eq!(
            serde_json::to_string(&e).unwrap(),
            r#"{"op":"and","args":[{"op":"from","from":{"type":"name","name":"kai"}},{"op":"since","at":{"type":"ago","ms":5}},{"op":"not","arg":{"op":"reply","value":true}}]}"#
        );
        assert_eq!(serde_json::to_string(&TimeOverride::Real).unwrap(), r#"{"mode":"real"}"#);
        assert_eq!(
            serde_json::to_string(&TimeRule::Round { round_min: 30 }).unwrap(),
            r#"{"mode":"round","round_min":30}"#
        );
        // skipped fields stay skipped; flatten puts the kind's fields first
        let m = Entity {
            kind: EntityKind::Mention { target_type: MentionTarget::Front, target_id: None },
            offset: 1,
            length: 2,
        };
        assert_eq!(
            serde_json::to_string(&m).unwrap(),
            r#"{"type":"mention","target_type":"front","offset":1,"length":2}"#
        );
        assert_eq!(
            serde_json::to_string(&Frame::Pull { scope: "s".into(), after: 3 }).unwrap(),
            r#"{"t":"pull","scope":"s","after":3}"#
        );
        // and into a Value the same way
        assert_eq!(serde_json::to_value(&TimeRef::Date { date: "2026-01-02".into() }).unwrap()["type"], "date");
    }

    #[test]
    fn reads_the_tag_anywhere_with_defaults_and_unknown_fields() {
        let e: Expr = serde_json::from_str(r#"{"value":"x","op":"tag"}"#).unwrap();
        assert_eq!(e, Expr::Tag { value: "x".into() });
        let r: TimeRule = serde_json::from_str(r#"{"mode":"jitter","extra":[1,2]}"#).unwrap();
        assert_eq!(r, TimeRule::Jitter { jitter_min: 15 });
        let u: TimeOverride = serde_json::from_str(r#"{"mode":"hide","offset_ms":4}"#).unwrap();
        assert_eq!(u, TimeOverride::Hide);
        let m: Entity = serde_json::from_str(r#"{"offset":1,"type":"text_link","length":2,"url":"u"}"#).unwrap();
        assert_eq!(m, Entity { kind: EntityKind::TextLink { url: "u".into() }, offset: 1, length: 2 });
        let f: Frame = serde_json::from_value(serde_json::json!({"to":1,"scope":"s","t":"caught","digest":{"count":0,"xor":"00000000000000000000000000000000"}})).unwrap();
        assert!(matches!(f, Frame::Caught { to: 1, .. }));
    }

    #[test]
    fn refuses_what_serde_tag_refused() {
        for bad in
            [r#"{"value":"x"}"#, r#"{"op":3,"value":"x"}"#, r#"{"op":"nope"}"#, r#"{"op":"tag"}"#, r#"[1]"#, r#""tag""#]
        {
            assert!(serde_json::from_str::<Expr>(bad).is_err(), "{bad}");
        }
        assert!(serde_json::from_str::<Entity>(r#"{"type":"bold","offset":1}"#).is_err());
        assert!(serde_json::from_str::<Entity>(r#"{"type":"bold","offset":-1,"length":1}"#).is_err());
    }
}
