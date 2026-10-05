package claude

import (
	"bufio"
	"bytes"
	"errors"
	"io"
)

// MaxLine is the longest stream-json line that is parsed. Longer lines are skipped
// (they cannot be parsed once cut) and counted.
const MaxLine = 16 << 20

// ReadLines calls fn for each non-empty line of r, without the line end. fn must
// not keep the slice. Lines longer than max bytes are skipped and counted.
func ReadLines(r io.Reader, max int, fn func(line []byte)) (oversized int, err error) {
	br := bufio.NewReaderSize(r, 64<<10)
	var buf []byte
	skipping := false
	for {
		chunk, err := br.ReadSlice('\n')
		if !skipping {
			if len(buf)+len(chunk) > max {
				skipping = true
				oversized++
				buf = buf[:0]
			} else {
				buf = append(buf, chunk...)
			}
		}
		if errors.Is(err, bufio.ErrBufferFull) {
			continue
		}
		if line := bytes.TrimRight(buf, "\r\n"); !skipping && len(line) > 0 {
			fn(line)
		}
		buf, skipping = buf[:0], false
		if err != nil {
			if errors.Is(err, io.EOF) {
				return oversized, nil
			}
			return oversized, err
		}
	}
}
