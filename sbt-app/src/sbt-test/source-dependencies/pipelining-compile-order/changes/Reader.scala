package example

object Reader {
  def read: Int = ByteArrayAccess.getInt() + 1
}
