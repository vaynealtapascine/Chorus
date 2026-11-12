<script lang="ts">
  // The CHORUS wordmark with the mark in place of the O (Figma F1.1), outlined from the owner's logo
  // file; the same paths as Android's res/drawable/chorus_wordmark.xml. Cream, for the teal scenes.
  // An image file, not inline paths: the outlines are 12 KB that the script bundle doesn't have to carry.
  import url from './wordmark.svg';
  let { width = 272 }: { width?: number } = $props();
</script>

<img src={url} alt="Chorus" {width} height={Math.round(width * 1215 / 3754)} style="height: auto; max-width: 100%" />
